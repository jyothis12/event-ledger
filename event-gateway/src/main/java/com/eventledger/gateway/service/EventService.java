package com.eventledger.gateway.service;

import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.dto.EventResponse;
import com.eventledger.gateway.model.Event;
import com.eventledger.gateway.repository.EventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Slf4j
public class EventService {

    private final EventRepository eventRepository;
    private final AccountServiceClient accountServiceClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    private Counter eventsReceivedCounter;
    private Counter duplicateEventsCounter;
    private Counter eventsProcessedCounter;
    private Counter eventsFailedCounter;

    public EventService(EventRepository eventRepository,
                        AccountServiceClient accountServiceClient,
                        ObjectMapper objectMapper,
                        MeterRegistry meterRegistry) {
        this.eventRepository = eventRepository;
        this.accountServiceClient = accountServiceClient;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void initMetrics() {
        eventsReceivedCounter = Counter.builder("events.received.total")
                .description("Total events received")
                .register(meterRegistry);
        duplicateEventsCounter = Counter.builder("events.duplicate.total")
                .description("Duplicate events rejected")
                .register(meterRegistry);
        eventsProcessedCounter = Counter.builder("events.processed.total")
                .description("Events successfully processed")
                .register(meterRegistry);
        eventsFailedCounter = Counter.builder("events.failed.total")
                .description("Events that failed processing")
                .register(meterRegistry);
    }

    /**
     * Submit a new event. Idempotent: if eventId already exists, returns the original event.
     * Returns a pair: (response, isNew)
     */
    @Transactional
    public EventSubmitResult submitEvent(EventRequest request) {
        eventsReceivedCounter.increment();
        log.info("Processing event eventId={} accountId={} type={} amount={}",
                request.getEventId(), request.getAccountId(), request.getType(), request.getAmount());

        // Idempotency check
        Optional<Event> existing = eventRepository.findByEventId(request.getEventId());
        if (existing.isPresent()) {
            duplicateEventsCounter.increment();
            log.info("Duplicate event received: eventId={} — returning existing", request.getEventId());
            return new EventSubmitResult(toResponse(existing.get()), false);
        }

        // Persist the event in Gateway's own DB first
        String metadataJson = null;
        if (request.getMetadata() != null) {
            try {
                metadataJson = objectMapper.writeValueAsString(request.getMetadata());
            } catch (JsonProcessingException e) {
                log.warn("Could not serialize metadata for eventId={}", request.getEventId());
            }
        }

        Event event = Event.builder()
                .eventId(request.getEventId())
                .accountId(request.getAccountId())
                .type(Event.EventType.valueOf(request.getType()))
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .eventTimestamp(request.getEventTimestamp())
                .receivedAt(Instant.now())
                .metadataJson(metadataJson)
                .build();

        eventRepository.save(event);
        log.info("Event persisted locally: eventId={}", event.getEventId());

        // Call Account Service to apply the transaction
        try {
            accountServiceClient.applyTransaction(
                    event.getAccountId(),
                    event.getEventId(),
                    event.getType().name(),
                    event.getAmount(),
                    event.getCurrency(),
                    event.getEventTimestamp()
            );
            eventsProcessedCounter.increment();
        } catch (Exception ex) {
            eventsFailedCounter.increment();
            // Re-throw to let controller handle HTTP response
            throw ex;
        }

        return new EventSubmitResult(toResponse(event), true);
    }

    @Transactional(readOnly = true)
    public Optional<EventResponse> getEvent(String eventId) {
        return eventRepository.findByEventId(eventId).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public List<EventResponse> getEventsByAccount(String accountId) {
        return eventRepository.findByAccountIdOrderByEventTimestampAsc(accountId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private EventResponse toResponse(Event event) {
        Map<String, Object> metadata = null;
        if (event.getMetadataJson() != null) {
            try {
                metadata = objectMapper.readValue(event.getMetadataJson(), Map.class);
            } catch (JsonProcessingException e) {
                log.warn("Could not deserialize metadata for eventId={}", event.getEventId());
            }
        }
        return EventResponse.builder()
                .eventId(event.getEventId())
                .accountId(event.getAccountId())
                .type(event.getType().name())
                .amount(event.getAmount())
                .currency(event.getCurrency())
                .eventTimestamp(event.getEventTimestamp())
                .receivedAt(event.getReceivedAt())
                .metadata(metadata)
                .build();
    }

    public record EventSubmitResult(EventResponse response, boolean isNew) {}
}
