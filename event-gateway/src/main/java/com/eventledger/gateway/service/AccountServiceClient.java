package com.eventledger.gateway.service;

import com.eventledger.gateway.filter.TraceFilter;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Service
@Slf4j
public class AccountServiceClient {

    private final RestTemplate restTemplate;
    private final String accountServiceUrl;

    public AccountServiceClient(
            RestTemplate restTemplate,
            @Value("${account.service.url}") String accountServiceUrl) {
        this.restTemplate = restTemplate;
        this.accountServiceUrl = accountServiceUrl;
    }

    @CircuitBreaker(name = "accountService", fallbackMethod = "applyTransactionFallback")
    @Retry(name = "accountService")
    public void applyTransaction(String accountId, String eventId, String type,
                                  BigDecimal amount, String currency, Instant eventTimestamp) {
        String traceId = MDC.get(TraceFilter.TRACE_ID_MDC_KEY);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (traceId != null) {
            headers.set(TraceFilter.TRACE_ID_HEADER, traceId);
        }

        Map<String, Object> body = Map.of(
            "eventId", eventId,
            "type", type,
            "amount", amount,
            "currency", currency,
            "eventTimestamp", eventTimestamp.toString()
        );

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        String url = accountServiceUrl + "/accounts/" + accountId + "/transactions";
        log.info("Calling Account Service: POST {} eventId={} traceId={}", url, eventId, traceId);

        restTemplate.postForEntity(url, entity, Map.class);
        log.info("Account Service transaction applied successfully for eventId={}", eventId);
    }

    public void applyTransactionFallback(String accountId, String eventId, String type,
                                          BigDecimal amount, String currency, Instant eventTimestamp,
                                          Exception ex) {
        log.error("Circuit breaker OPEN: Account Service unavailable for eventId={}. Cause: {}",
                eventId, ex.getMessage());
        throw new AccountServiceUnavailableException(
                "Account Service is currently unavailable. Please try again later.", ex);
    }

    @CircuitBreaker(name = "accountService", fallbackMethod = "getBalanceFallback")
    public Map<String, Object> getBalance(String accountId) {
        String traceId = MDC.get(TraceFilter.TRACE_ID_MDC_KEY);

        HttpHeaders headers = new HttpHeaders();
        if (traceId != null) {
            headers.set(TraceFilter.TRACE_ID_HEADER, traceId);
        }

        String url = accountServiceUrl + "/accounts/" + accountId + "/balance";
        log.info("Calling Account Service: GET {} traceId={}", url, traceId);

        ResponseEntity<Map> response = restTemplate.exchange(
            url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        return response.getBody();
    }

    public Map<String, Object> getBalanceFallback(String accountId, Exception ex) {
        log.error("Circuit breaker OPEN: Cannot get balance for accountId={}. Cause: {}", accountId, ex.getMessage());
        throw new AccountServiceUnavailableException(
                "Account Service is currently unavailable. Balance cannot be retrieved.", ex);
    }

    public static class AccountServiceUnavailableException extends RuntimeException {
        public AccountServiceUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
