package dev.polina.copilot.adapter.in.rest;

import dev.polina.copilot.application.IngestEventService;
import dev.polina.copilot.application.IngestResult;
import dev.polina.copilot.domain.IncidentEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Граница транзакции приёма: lock, проверки и все INSERT'ы — одна транзакция, COMMIT до ответа 202.
 * Вызывать только снаружи этого класса: @Transactional работает через прокси,
 * и вызов из того же класса (self-invocation) прошёл бы мимо транзакции.
 */
@Component
public class TransactionalIngestion {

    private final IngestEventService service;

    public TransactionalIngestion(IngestEventService service) {
        this.service = service;
    }

    @Transactional
    public IngestResult ingest(IncidentEvent event) {
        return service.ingest(event);
    }
}
