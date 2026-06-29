package com.eventledger.gateway.service;

import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.dto.EventResponse;
import com.eventledger.gateway.model.Event;
import com.eventledger.gateway.repository.EventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private AccountServiceClient accountServiceClient;

    private EventService eventService;

    @BeforeEach
    void setUp() {
        eventService = new EventService(eventRepository, accountServiceClient,
                new ObjectMapper(), new SimpleMeterRegistry());
        eventService.initMetrics();
    }

    @Test
    void shouldReturnExistingEventOnDuplicate() {
        Event existing = Event.builder()
                .eventId("evt-dup")
                .accountId("acct-1")
                .type(Event.EventType.CREDIT)
                .amount(BigDecimal.valueOf(100))
                .currency("USD")
                .eventTimestamp(Instant.parse("2026-01-01T10:00:00Z"))
                .receivedAt(Instant.now())
                .build();
        when(eventRepository.findByEventId("evt-dup")).thenReturn(Optional.of(existing));

        EventRequest req = EventRequest.builder()
                .eventId("evt-dup")
                .accountId("acct-1")
                .type("CREDIT")
                .amount(BigDecimal.valueOf(100))
                .currency("USD")
                .eventTimestamp(Instant.parse("2026-01-01T10:00:00Z"))
                .build();

        EventService.EventSubmitResult result = eventService.submitEvent(req);

        assertThat(result.isNew()).isFalse();
        assertThat(result.response().getEventId()).isEqualTo("evt-dup");
        verifyNoInteractions(accountServiceClient);
        verify(eventRepository, never()).save(any());
    }

    @Test
    void shouldPersistNewEventAndCallAccountService() {
        when(eventRepository.findByEventId("evt-new")).thenReturn(Optional.empty());
        when(eventRepository.save(any(Event.class))).thenAnswer(i -> i.getArgument(0));

        EventRequest req = EventRequest.builder()
                .eventId("evt-new")
                .accountId("acct-2")
                .type("DEBIT")
                .amount(BigDecimal.valueOf(50))
                .currency("USD")
                .eventTimestamp(Instant.parse("2026-01-01T12:00:00Z"))
                .build();

        EventService.EventSubmitResult result = eventService.submitEvent(req);

        assertThat(result.isNew()).isTrue();
        assertThat(result.response().getEventId()).isEqualTo("evt-new");
        verify(eventRepository).save(any(Event.class));
        verify(accountServiceClient).applyTransaction(
                eq("acct-2"), eq("evt-new"), eq("DEBIT"),
                eq(BigDecimal.valueOf(50)), eq("USD"), any(Instant.class));
    }

    @Test
    void shouldPropagateExceptionWhenAccountServiceFails() {
        when(eventRepository.findByEventId("evt-fail")).thenReturn(Optional.empty());
        when(eventRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        doThrow(new AccountServiceClient.AccountServiceUnavailableException("down", new RuntimeException()))
                .when(accountServiceClient).applyTransaction(any(), any(), any(), any(), any(), any());

        EventRequest req = EventRequest.builder()
                .eventId("evt-fail")
                .accountId("acct-3")
                .type("CREDIT")
                .amount(BigDecimal.valueOf(200))
                .currency("USD")
                .eventTimestamp(Instant.now())
                .build();

        assertThatThrownBy(() -> eventService.submitEvent(req))
                .isInstanceOf(AccountServiceClient.AccountServiceUnavailableException.class);
    }

    @Test
    void shouldReturnEventsOrderedByTimestamp() {
        Instant t1 = Instant.parse("2026-01-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-01-01T11:00:00Z");

        Event e1 = Event.builder().eventId("evt-1").accountId("acct-4")
                .type(Event.EventType.CREDIT).amount(BigDecimal.TEN).currency("USD")
                .eventTimestamp(t1).receivedAt(Instant.now()).build();
        Event e2 = Event.builder().eventId("evt-2").accountId("acct-4")
                .type(Event.EventType.DEBIT).amount(BigDecimal.ONE).currency("USD")
                .eventTimestamp(t2).receivedAt(Instant.now()).build();

        when(eventRepository.findByAccountIdOrderByEventTimestampAsc("acct-4"))
                .thenReturn(List.of(e1, e2));

        List<EventResponse> events = eventService.getEventsByAccount("acct-4");

        assertThat(events).hasSize(2);
        assertThat(events.get(0).getEventId()).isEqualTo("evt-1");
        assertThat(events.get(1).getEventId()).isEqualTo("evt-2");
    }

    @Test
    void shouldReturnEmptyForUnknownEventId() {
        when(eventRepository.findByEventId("not-found")).thenReturn(Optional.empty());

        Optional<EventResponse> result = eventService.getEvent("not-found");

        assertThat(result).isEmpty();
    }

    @Test
    void shouldHandleEventWithMetadata() {
        when(eventRepository.findByEventId("evt-meta")).thenReturn(Optional.empty());
        when(eventRepository.save(any(Event.class))).thenAnswer(i -> i.getArgument(0));

        EventRequest req = EventRequest.builder()
                .eventId("evt-meta")
                .accountId("acct-5")
                .type("CREDIT")
                .amount(BigDecimal.valueOf(75))
                .currency("USD")
                .eventTimestamp(Instant.now())
                .metadata(Map.of("source", "batch", "batchId", "B-001"))
                .build();

        EventService.EventSubmitResult result = eventService.submitEvent(req);

        assertThat(result.isNew()).isTrue();
        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository).save(captor.capture());
        assertThat(captor.getValue().getMetadataJson()).contains("source").contains("batch");
    }
}