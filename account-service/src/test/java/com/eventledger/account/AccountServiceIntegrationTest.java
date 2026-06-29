package com.eventledger.account;

import com.eventledger.account.repository.AccountRepository;
import com.eventledger.account.repository.TransactionRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AccountServiceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    private String txBody(String eventId, String type, double amount, String ts) {
        return String.format(
            "{\"eventId\":\"%s\",\"type\":\"%s\",\"amount\":%s,\"currency\":\"USD\",\"eventTimestamp\":\"%s\"}",
            eventId, type, amount, ts);
    }

    // ─────────────────────────────────────────────────────────────
    // Core: Apply CREDIT transaction
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void shouldApplyCreditTransaction() throws Exception {
        mockMvc.perform(post("/accounts/acct-1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(txBody("evt-c1", "CREDIT", 500.0, "2026-01-01T10:00:00Z")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance").value(500.0))
                .andExpect(jsonPath("$.applied").value(true));
    }

    // ─────────────────────────────────────────────────────────────
    // Core: Balance computation — CREDIT and DEBIT
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    void shouldComputeBalanceCorrectly() throws Exception {
        mockMvc.perform(post("/accounts/acct-2/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(txBody("evt-credit", "CREDIT", 1000.0, "2026-01-01T10:00:00Z")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/accounts/acct-2/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(txBody("evt-debit", "DEBIT", 250.0, "2026-01-01T11:00:00Z")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/accounts/acct-2/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(750.0));
    }

    // ─────────────────────────────────────────────────────────────
    // Core: Idempotency
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    void shouldHandleDuplicateTransactionIdempotently() throws Exception {
        String body = txBody("evt-dup-acct", "CREDIT", 300.0, "2026-01-01T10:00:00Z");

        mockMvc.perform(post("/accounts/acct-3/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.applied").value(true));

        // Second call with same eventId
        mockMvc.perform(post("/accounts/acct-3/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false));

        // Balance should still be 300, not 600
        mockMvc.perform(get("/accounts/acct-3/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(300.0));

        // Only one transaction record exists
        assertThat(transactionRepository.findAll().stream()
                .filter(t -> t.getEventId().equals("evt-dup-acct"))
                .count()).isEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────────
    // Core: Out-of-order events — balance remains correct
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(4)
    void shouldComputeCorrectBalanceForOutOfOrderEvents() throws Exception {
        // Submit with later timestamp first
        mockMvc.perform(post("/accounts/acct-4/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(txBody("evt-t3", "CREDIT", 200.0, "2026-01-01T12:00:00Z")))
                .andExpect(status().isCreated());

        // Submit with earliest timestamp last
        mockMvc.perform(post("/accounts/acct-4/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(txBody("evt-t1", "CREDIT", 100.0, "2026-01-01T10:00:00Z")))
                .andExpect(status().isCreated());

        // Submit middle timestamp
        mockMvc.perform(post("/accounts/acct-4/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(txBody("evt-t2", "DEBIT", 50.0, "2026-01-01T11:00:00Z")))
                .andExpect(status().isCreated());

        // Balance = 100 + 200 - 50 = 250 regardless of arrival order
        mockMvc.perform(get("/accounts/acct-4/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(250.0));
    }

    // ─────────────────────────────────────────────────────────────
    // Core: Account details with ordered transactions
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    void shouldReturnAccountDetailsWithTransactionsOrderedByTimestamp() throws Exception {
        mockMvc.perform(post("/accounts/acct-5/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(txBody("evt-late", "CREDIT", 100.0, "2026-01-01T15:00:00Z")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/accounts/acct-5/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(txBody("evt-early", "CREDIT", 50.0, "2026-01-01T09:00:00Z")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/accounts/acct-5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions", hasSize(2)))
                .andExpect(jsonPath("$.transactions[0].eventId").value("evt-early"))
                .andExpect(jsonPath("$.transactions[1].eventId").value("evt-late"))
                .andExpect(jsonPath("$.balance").value(150.0));
    }

    // ─────────────────────────────────────────────────────────────
    // 404 for unknown account
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(6)
    void shouldReturn404ForUnknownAccount() throws Exception {
        mockMvc.perform(get("/accounts/no-such-account/balance"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/accounts/no-such-account"))
                .andExpect(status().isNotFound());
    }

    // ─────────────────────────────────────────────────────────────
    // Trace propagation: trace ID in logs (verify header accepted)
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(7)
    void shouldAcceptTraceIdHeader() throws Exception {
        mockMvc.perform(post("/accounts/acct-trace/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Trace-Id", "upstream-trace-xyz")
                        .content(txBody("evt-trace-acct", "CREDIT", 100.0, "2026-01-01T10:00:00Z")))
                .andExpect(status().isCreated());
        // If it completes without error, trace filter handled it correctly
    }

    // ─────────────────────────────────────────────────────────────
    // Health check
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(8)
    void shouldReturnHealthStatus() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("account-service"));
    }
}
