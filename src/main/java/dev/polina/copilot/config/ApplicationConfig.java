package dev.polina.copilot.config;

import dev.polina.copilot.application.EventSanitizer;
import dev.polina.copilot.application.IncidentQueryService;
import dev.polina.copilot.application.IngestEventService;
import dev.polina.copilot.port.IncidentRepository;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Сборка use case'ов. Классы из application не знают о Spring (ArchUnit запрещает @Service),
 * поэтому бины объявляются здесь, а зависимости передаются через конструктор.
 */
@Configuration
public class ApplicationConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    EventSanitizer eventSanitizer() {
        return new EventSanitizer();
    }

    @Bean
    IngestEventService ingestEventService(IncidentRepository repository, EventSanitizer sanitizer, Clock clock,
                                          @Value("${copilot.ingestion.correlation-window}") Duration window) {
        return new IngestEventService(repository, sanitizer, clock, window);
    }

    @Bean
    IncidentQueryService incidentQueryService(IncidentRepository repository) {
        return new IncidentQueryService(repository);
    }
}
