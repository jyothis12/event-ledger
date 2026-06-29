package com.eventledger.gateway.controller;

import com.eventledger.gateway.dto.ErrorResponse;
import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.dto.EventResponse;
import com.eventledger.gateway.service.AccountServiceClient;
import com.eventledger.gateway.service.EventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
public class EventController {

    private final EventService eventService;
    private final AccountServiceClient accountServiceClient;

    /**
     * POST /events — Submit a transaction event
     */
    @PostMapping("/events")
    public ResponseEntity<?> submitEvent(@Valid @RequestBody EventRequest request) {
        try {
            EventService.EventSubmitResult result = eventService.submitEvent(request);
            if (result.isNew()) {
                return ResponseEntity.status(HttpStatus.CREATED).body(result.response());
            } else {
                // Duplicate: return 200 with original event
                return ResponseEntity.ok(result.response());
            }
        } catch (AccountServiceClient.AccountServiceUnavailableException ex) {
            log.error("Account Service unavailable while processing eventId={}: {}", request.getEventId(), ex.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ErrorResponse.builder()
                            .status(503)
                            .error("Service Unavailable")
                            .message(ex.getMessage())
                            .timestamp(Instant.now())
                            .build());
        }
    }

    /**
     * GET /events/{id} — Retrieve a single event (Gateway-only, no Account Service call)
     */
    @GetMapping("/events/{id}")
    public ResponseEntity<?> getEvent(@PathVariable String id) {
        return eventService.getEvent(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ErrorResponse.builder()
                                .status(404)
                                .error("Not Found")
                                .message("Event not found: " + id)
                                .timestamp(Instant.now())
                                .build()));
    }

    /**
     * GET /events?account={accountId} — List events for an account, ordered by eventTimestamp
     * Works even when Account Service is down (reads from Gateway's own DB)
     */
    @GetMapping("/events")
    public ResponseEntity<List<EventResponse>> getEventsByAccount(
            @RequestParam(name = "account") String accountId) {
        List<EventResponse> events = eventService.getEventsByAccount(accountId);
        return ResponseEntity.ok(events);
    }

    /**
     * GET /health — Health check
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "event-gateway",
                "timestamp", Instant.now().toString()
        ));
    }
}
