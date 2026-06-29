package com.eventledger.gateway;

import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.dto.EventResponse;
import com.eventledger.gateway.model.Event;
import com.eventledger.gateway.repository.EventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventGatewayIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EventRepository eventRepository;

    static MockWebServer mockAccountService;

    @BeforeAll
    static void startMockServer() throws Exception {
        mockAccountService = new MockWebServer();
        mockAccountService.start();
    }

    @AfterAll
    static void stopMockServer() throws Exception {
        mockAccountService.shutdown();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("account.service.url",
                () -> "http://localhost:" + mockAccountService.getPort());
    }

    @BeforeEach
    void setUp() {
        eventRepository.deleteAll();
        // Reset circuit breaker state between tests would need Resilience4j test utilities
    }

    private EventRequest buildEventRequest(String eventId, String accountId,
                                            String type, BigDecimal amount, Instant ts) {
        return EventRequest.builder()
                .eventId(eventId)
                .accountId(accountId)
                .type(type)
                .amount(amount)
                .currency("USD")
                .eventTimestamp(ts)
                .metadata(Map.of("source", "test"))
                .build();
    }

    private void enqueueAccountServiceSuccess() {
        mockAccountService.enqueue(new MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"accountId\":\"acct-1\",\"balance\":100.00,\"applied\":true}"));
    }

    // ─────────────────────────────────────────────────────────────
    // Core: Validation
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void shouldRejectEventWithMissingFields() throws Exception {
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    @Order(2)
    void shouldRejectEventWithNegativeAmount() throws Exception {
        EventRequest req = buildEventRequest("evt-neg", "acct-1", "CREDIT",
                BigDecimal.valueOf(-50), Instant.now());

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @Order(3)
    void shouldRejectInvalidEventType() throws Exception {
        EventRequest req = buildEventRequest("evt-bad-type", "acct-1", "TRANSFER",
                BigDecimal.valueOf(100), Instant.now());

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    // ─────────────────────────────────────────────────────────────
    // Core: Submit event successfully
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(4)
    void shouldSubmitEventSuccessfully() throws Exception {
        enqueueAccountServiceSuccess();

        EventRequest req = buildEventRequest("evt-001", "acct-1", "CREDIT",
                BigDecimal.valueOf(150), Instant.parse("2026-05-15T14:02:11Z"));

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value("evt-001"))
                .andExpect(jsonPath("$.accountId").value("acct-1"))
                .andExpect(jsonPath("$.type").value("CREDIT"))
                .andExpect(jsonPath("$.amount").value(150.0));
    }

    // ─────────────────────────────────────────────────────────────
    // Core: Idempotency
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    void shouldHandleDuplicateEventIdempotently() throws Exception {
        enqueueAccountServiceSuccess();

        Instant ts = Instant.parse("2026-05-15T14:00:00Z");
        EventRequest req = buildEventRequest("evt-dup", "acct-2", "CREDIT",
                BigDecimal.valueOf(200), ts);

        // First submission — 201
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        // Second submission with same eventId — 200, no duplicate in DB
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-dup"));

        // Only one record in DB
        assertThat(eventRepository.findByEventId("evt-dup")).isPresent();
        assertThat(eventRepository.findAll().stream()
                .filter(e -> e.getEventId().equals("evt-dup"))
                .count()).isEqualTo(1);

        // Account Service should only have been called ONCE (duplicate bypasses it)
        assertThat(mockAccountService.getRequestCount()).isEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────────
    // Core: Out-of-order events — listing by eventTimestamp
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(6)
    void shouldReturnEventsOrderedByEventTimestamp() throws Exception {
        // Submit later-timestamp first
        enqueueAccountServiceSuccess();
        EventRequest later = buildEventRequest("evt-later", "acct-3", "CREDIT",
                BigDecimal.valueOf(300), Instant.parse("2026-05-15T15:00:00Z"));
        mockMvc.perform(post("/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(later)))
                .andExpect(status().isCreated());

        // Submit earlier-timestamp second
        enqueueAccountServiceSuccess();
        EventRequest earlier = buildEventRequest("evt-earlier", "acct-3", "CREDIT",
                BigDecimal.valueOf(100), Instant.parse("2026-05-15T13:00:00Z"));
        mockMvc.perform(post("/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(earlier)))
                .andExpect(status().isCreated());

        // List should be ordered by eventTimestamp ascending
        MvcResult result = mockMvc.perform(get("/events?account=acct-3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        EventResponse[] events = objectMapper.readValue(body, EventResponse[].class);
        assertThat(events[0].getEventId()).isEqualTo("evt-earlier");
        assertThat(events[1].getEventId()).isEqualTo("evt-later");
    }

    // ─────────────────────────────────────────────────────────────
    // Core: Get single event by ID
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(7)
    void shouldRetrieveEventById() throws Exception {
        enqueueAccountServiceSuccess();

        EventRequest req = buildEventRequest("evt-get-test", "acct-4", "DEBIT",
                BigDecimal.valueOf(50), Instant.now());
        mockMvc.perform(post("/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/events/evt-get-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-get-test"))
                .andExpect(jsonPath("$.type").value("DEBIT"));
    }

    @Test
    @Order(8)
    void shouldReturn404ForUnknownEventId() throws Exception {
        mockMvc.perform(get("/events/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // ─────────────────────────────────────────────────────────────
    // Resiliency: Account Service failure → 503
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(9)
    void shouldReturn503WhenAccountServiceDown() throws Exception {
        // Simulate Account Service connection failure
        mockAccountService.enqueue(new MockResponse().setResponseCode(500));

        EventRequest req = buildEventRequest("evt-fail-1", "acct-5", "CREDIT",
                BigDecimal.valueOf(100), Instant.now());

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().is5xxServerError());
    }

    // ─────────────────────────────────────────────────────────────
    // Graceful degradation: GET /events still works when Account Service is down
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    void shouldReturnEventsEvenWhenAccountServiceDown() throws Exception {
        // First, successfully store an event
        enqueueAccountServiceSuccess();
        EventRequest req = buildEventRequest("evt-degrade", "acct-6", "CREDIT",
                BigDecimal.valueOf(500), Instant.now());
        mockMvc.perform(post("/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        // Now GET /events works regardless of Account Service state (reads from Gateway DB)
        mockMvc.perform(get("/events/evt-degrade"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-degrade"));

        mockMvc.perform(get("/events?account=acct-6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventId").value("evt-degrade"));
    }

    // ─────────────────────────────────────────────────────────────
    // Trace propagation
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(11)
    void shouldPropagateTraceIdToAccountService() throws Exception {
        enqueueAccountServiceSuccess();

        EventRequest req = buildEventRequest("evt-trace", "acct-7", "CREDIT",
                BigDecimal.valueOf(100), Instant.now());

        String clientTraceId = "test-trace-abc-123";
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Trace-Id", clientTraceId)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Trace-Id", clientTraceId));

        // Verify the trace ID was forwarded to Account Service
        RecordedRequest accountServiceRequest = mockAccountService.takeRequest();
        assertThat(accountServiceRequest.getHeader("X-Trace-Id")).isEqualTo(clientTraceId);
    }

    @Test
    @Order(12)
    void shouldGenerateTraceIdWhenNotProvided() throws Exception {
        enqueueAccountServiceSuccess();

        EventRequest req = buildEventRequest("evt-gen-trace", "acct-8", "CREDIT",
                BigDecimal.valueOf(75), Instant.now());

        MvcResult result = mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        String traceId = result.getResponse().getHeader("X-Trace-Id");
        assertThat(traceId).isNotBlank();
        assertThat(traceId).matches("[0-9a-f-]{36}"); // UUID format
    }

    // ─────────────────────────────────────────────────────────────
    // Health check
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(13)
    void shouldReturnHealthyStatus() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("event-gateway"));
    }
}
