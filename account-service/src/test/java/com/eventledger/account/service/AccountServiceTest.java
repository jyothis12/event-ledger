package com.eventledger.account.service;

import com.eventledger.account.model.Account;
import com.eventledger.account.model.Transaction;
import com.eventledger.account.repository.AccountRepository;
import com.eventledger.account.repository.TransactionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    private AccountService accountService;

    @BeforeEach
    void setUp() {
        accountService = new AccountService(accountRepository, transactionRepository,
                new SimpleMeterRegistry());
        accountService.initMetrics();
    }

    // ─────────────────────────────────────────────────────────────
    // Idempotency: duplicate eventId returns existing balance, skips insert
    // ─────────────────────────────────────────────────────────────

    @Test
    void shouldSkipDuplicateTransaction() {
        when(transactionRepository.existsByEventId("evt-dup")).thenReturn(true);
        when(transactionRepository.computeBalance("acct-1")).thenReturn(BigDecimal.valueOf(500));

        AccountService.ApplyResult result = accountService.applyTransaction(
                "acct-1", "evt-dup", "CREDIT",
                BigDecimal.valueOf(500), "USD", Instant.now());

        assertThat(result.isNew()).isFalse();
        assertThat(result.balance()).isEqualByComparingTo("500");
        verify(transactionRepository, never()).save(any());
    }

    // ─────────────────────────────────────────────────────────────
    // New CREDIT transaction: account auto-created, balance computed
    // ─────────────────────────────────────────────────────────────

    @Test
    void shouldApplyNewCreditTransactionAndAutoCreateAccount() {
        when(transactionRepository.existsByEventId("evt-c1")).thenReturn(false);
        when(accountRepository.findById("acct-new")).thenReturn(Optional.empty());
        Account newAccount = Account.builder().accountId("acct-new")
                .balance(BigDecimal.ZERO).build();
        when(accountRepository.save(any(Account.class))).thenReturn(newAccount);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));
        when(transactionRepository.computeBalance("acct-new")).thenReturn(BigDecimal.valueOf(300));

        AccountService.ApplyResult result = accountService.applyTransaction(
                "acct-new", "evt-c1", "CREDIT",
                BigDecimal.valueOf(300), "USD", Instant.parse("2026-01-01T10:00:00Z"));

        assertThat(result.isNew()).isTrue();
        assertThat(result.balance()).isEqualByComparingTo("300");
        verify(transactionRepository).save(any(Transaction.class));
    }

    // ─────────────────────────────────────────────────────────────
    // Balance computation: always recomputed from all transactions
    // ─────────────────────────────────────────────────────────────

    @Test
    void shouldRecomputeBalanceFromAllTransactions() {
        when(transactionRepository.existsByEventId("evt-d1")).thenReturn(false);
        Account acc = Account.builder().accountId("acct-3").balance(BigDecimal.valueOf(1000)).build();
        when(accountRepository.findById("acct-3")).thenReturn(Optional.of(acc));
        when(transactionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        // Net = 1000 CREDIT - 250 DEBIT = 750
        when(transactionRepository.computeBalance("acct-3")).thenReturn(BigDecimal.valueOf(750));

        AccountService.ApplyResult result = accountService.applyTransaction(
                "acct-3", "evt-d1", "DEBIT",
                BigDecimal.valueOf(250), "USD", Instant.now());

        assertThat(result.balance()).isEqualByComparingTo("750");
    }

    // ─────────────────────────────────────────────────────────────
    // getBalance: returns empty Optional for unknown account
    // ─────────────────────────────────────────────────────────────

    @Test
    void shouldReturnEmptyBalanceForUnknownAccount() {
        when(accountRepository.existsById("no-such")).thenReturn(false);

        Optional<BigDecimal> balance = accountService.getBalance("no-such");

        assertThat(balance).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────
    // getBalance: returns correct balance for known account
    // ─────────────────────────────────────────────────────────────

    @Test
    void shouldReturnBalanceForKnownAccount() {
        when(accountRepository.existsById("acct-4")).thenReturn(true);
        when(transactionRepository.computeBalance("acct-4")).thenReturn(BigDecimal.valueOf(800));

        Optional<BigDecimal> balance = accountService.getBalance("acct-4");

        assertThat(balance).isPresent();
        assertThat(balance.get()).isEqualByComparingTo("800");
    }

    // ─────────────────────────────────────────────────────────────
    // getAccountDetails: returns ordered transactions
    // ─────────────────────────────────────────────────────────────

    @Test
    void shouldReturnAccountDetailsWithOrderedTransactions() {
        Instant t1 = Instant.parse("2026-01-01T09:00:00Z");
        Instant t2 = Instant.parse("2026-01-01T11:00:00Z");

        Account acc = Account.builder().accountId("acct-5")
                .balance(BigDecimal.valueOf(150))
                .build();
        acc.setCreatedAt(Instant.now());

        Transaction tx1 = Transaction.builder().eventId("evt-early").accountId("acct-5")
                .type(Transaction.TransactionType.CREDIT).amount(BigDecimal.valueOf(50))
                .currency("USD").eventTimestamp(t1).processedAt(Instant.now()).build();
        Transaction tx2 = Transaction.builder().eventId("evt-late").accountId("acct-5")
                .type(Transaction.TransactionType.CREDIT).amount(BigDecimal.valueOf(100))
                .currency("USD").eventTimestamp(t2).processedAt(Instant.now()).build();

        when(accountRepository.findById("acct-5")).thenReturn(Optional.of(acc));
        when(transactionRepository.findByAccountIdOrderByEventTimestampAsc("acct-5"))
                .thenReturn(List.of(tx1, tx2));
        when(transactionRepository.computeBalance("acct-5")).thenReturn(BigDecimal.valueOf(150));

        Optional<AccountService.AccountDetails> details = accountService.getAccountDetails("acct-5");

        assertThat(details).isPresent();
        assertThat(details.get().transactions()).hasSize(2);
        assertThat(details.get().transactions().get(0).getEventId()).isEqualTo("evt-early");
        assertThat(details.get().balance()).isEqualByComparingTo("150");
    }

    // ─────────────────────────────────────────────────────────────
    // getAccountDetails: returns empty Optional for unknown account
    // ─────────────────────────────────────────────────────────────

    @Test
    void shouldReturnEmptyDetailsForUnknownAccount() {
        when(accountRepository.findById("missing")).thenReturn(Optional.empty());

        Optional<AccountService.AccountDetails> details = accountService.getAccountDetails("missing");

        assertThat(details).isEmpty();
    }
}
