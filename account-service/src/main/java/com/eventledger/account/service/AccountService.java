package com.eventledger.account.service;

import com.eventledger.account.model.Account;
import com.eventledger.account.model.Transaction;
import com.eventledger.account.repository.AccountRepository;
import com.eventledger.account.repository.TransactionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class AccountService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final MeterRegistry meterRegistry;

    private Counter transactionsAppliedCounter;
    private Counter transactionsDuplicateCounter;

    public AccountService(AccountRepository accountRepository,
                          TransactionRepository transactionRepository,
                          MeterRegistry meterRegistry) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void initMetrics() {
        transactionsAppliedCounter = Counter.builder("transactions.applied.total")
                .description("Total transactions applied")
                .register(meterRegistry);
        transactionsDuplicateCounter = Counter.builder("transactions.duplicate.total")
                .description("Duplicate transactions skipped")
                .register(meterRegistry);
    }

    /**
     * Apply a transaction to an account.
     * Idempotent: if eventId already processed, returns existing result.
     * Balance is always recomputed from scratch to handle out-of-order events correctly.
     */
    @Transactional
    public ApplyResult applyTransaction(String accountId, String eventId,
                                         String type, BigDecimal amount,
                                         String currency, Instant eventTimestamp) {

        log.info("Applying transaction: eventId={} accountId={} type={} amount={}",
                eventId, accountId, type, amount);

        // Idempotency: check if already processed
        if (transactionRepository.existsByEventId(eventId)) {
            transactionsDuplicateCounter.increment();
            log.info("Duplicate transaction skipped: eventId={}", eventId);
            BigDecimal balance = transactionRepository.computeBalance(accountId);
            return new ApplyResult(false, balance);
        }

        // Ensure account exists (auto-create if first transaction for accountId)
        accountRepository.findById(accountId).orElseGet(() -> {
            log.info("Auto-creating account: accountId={}", accountId);
            return accountRepository.save(Account.builder()
                    .accountId(accountId)
                    .balance(BigDecimal.ZERO)
                    .build());
        });

        // Persist transaction
        Transaction tx = Transaction.builder()
                .eventId(eventId)
                .accountId(accountId)
                .type(Transaction.TransactionType.valueOf(type))
                .amount(amount)
                .currency(currency)
                .eventTimestamp(eventTimestamp)
                .processedAt(Instant.now())
                .build();

        transactionRepository.save(tx);

        // Recompute balance from all transactions (correct for out-of-order arrivals)
        BigDecimal newBalance = transactionRepository.computeBalance(accountId);

        // Update account balance
        Account account = accountRepository.findById(accountId).orElseThrow();
        account.setBalance(newBalance);
        account.setUpdatedAt(Instant.now());
        accountRepository.save(account);

        transactionsAppliedCounter.increment();
        log.info("Transaction applied: eventId={} accountId={} newBalance={}", eventId, accountId, newBalance);

        return new ApplyResult(true, newBalance);
    }

    @Transactional(readOnly = true)
    public Optional<BigDecimal> getBalance(String accountId) {
        if (!accountRepository.existsById(accountId)) {
            return Optional.empty();
        }
        return Optional.of(transactionRepository.computeBalance(accountId));
    }

    @Transactional(readOnly = true)
    public Optional<AccountDetails> getAccountDetails(String accountId) {
        return accountRepository.findById(accountId).map(account -> {
            List<Transaction> transactions = transactionRepository
                    .findByAccountIdOrderByEventTimestampAsc(accountId);
            BigDecimal balance = transactionRepository.computeBalance(accountId);
            return new AccountDetails(account.getAccountId(), balance, transactions, account.getCreatedAt());
        });
    }

    public record ApplyResult(boolean isNew, BigDecimal balance) {}

    public record AccountDetails(String accountId, BigDecimal balance,
                                  List<Transaction> transactions, Instant createdAt) {}
}
