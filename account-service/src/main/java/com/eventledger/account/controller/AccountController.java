package com.eventledger.account.controller;

import com.eventledger.account.model.Transaction;
import com.eventledger.account.service.AccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@Slf4j
public class AccountController {

    private final AccountService accountService;

    /**
     * POST /accounts/{accountId}/transactions — Apply a transaction
     */
    @PostMapping("/accounts/{accountId}/transactions")
    public ResponseEntity<Map<String, Object>> applyTransaction(
            @PathVariable String accountId,
            @RequestBody Map<String, Object> body) {

        String eventId = (String) body.get("eventId");
        String type = (String) body.get("type");
        BigDecimal amount = new BigDecimal(body.get("amount").toString());
        String currency = (String) body.get("currency");
        Instant eventTimestamp = Instant.parse((String) body.get("eventTimestamp"));

        log.info("Received transaction request: eventId={} accountId={} type={}", eventId, accountId, type);

        AccountService.ApplyResult result = accountService.applyTransaction(
                accountId, eventId, type, amount, currency, eventTimestamp);

        return ResponseEntity
                .status(result.isNew() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(Map.of(
                        "accountId", accountId,
                        "eventId", eventId,
                        "balance", result.balance(),
                        "applied", result.isNew()
                ));
    }

    /**
     * GET /accounts/{accountId}/balance — Get current balance
     */
    @GetMapping("/accounts/{accountId}/balance")
    public ResponseEntity<Map<String, Object>> getBalance(@PathVariable String accountId) {
        return accountService.getBalance(accountId)
                .<ResponseEntity<Map<String, Object>>>map(balance -> ResponseEntity.ok(Map.of(
                        "accountId", accountId,
                        "balance", balance,
                        "timestamp", Instant.now().toString()
                )))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of(
                                "error", "Account not found: " + accountId,
                                "status", 404
                        )));
    }

    /**
     * GET /accounts/{accountId} — Get account details and recent transactions
     */
    @GetMapping("/accounts/{accountId}")
    public ResponseEntity<Map<String, Object>> getAccount(@PathVariable String accountId) {
        return accountService.getAccountDetails(accountId)
                .<ResponseEntity<Map<String, Object>>>map(details -> ResponseEntity.ok(Map.of(
                        "accountId", details.accountId(),
                        "balance", details.balance(),
                        "createdAt", details.createdAt().toString(),
                        "transactions", details.transactions().stream()
                                .map(t -> Map.of(
                                        "eventId", t.getEventId(),
                                        "type", t.getType().name(),
                                        "amount", t.getAmount(),
                                        "currency", t.getCurrency(),
                                        "eventTimestamp", t.getEventTimestamp().toString(),
                                        "processedAt", t.getProcessedAt().toString()
                                ))
                                .collect(Collectors.toList())
                )))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of(
                                "error", "Account not found: " + accountId,
                                "status", 404
                        )));
    }

    /**
     * GET /health — Health check
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "account-service",
                "timestamp", Instant.now().toString()
        ));
    }
}
