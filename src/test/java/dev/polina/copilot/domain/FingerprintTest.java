package dev.polina.copilot.domain;

import static dev.polina.copilot.domain.TestEvents.event;
import static org.assertj.core.api.Assertions.assertThat;

import dev.polina.copilot.domain.IncidentEvent.ErrorDetails;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FingerprintTest {

    @Test
    @DisplayName("та же ошибка с другим eventId, временем и текстом → тот же fingerprint")
    void sameErrorGivesSameFingerprint() {
        IncidentEvent first = event().eventId("evt-1").summary("first").build();
        IncidentEvent second = event().eventId("evt-2").summary("second")
                .occurredAt(Instant.parse("2026-10-15T11:00:00Z")).build();

        assertThat(Fingerprint.of(first)).isEqualTo(Fingerprint.of(second));
    }

    @Test
    @DisplayName("регистр и лишние пробелы не влияют")
    void ignoresCaseAndWhitespace() {
        IncidentEvent normal = event().service("order-service").type("DB_POOL_EXHAUSTED").build();
        IncidentEvent messy = event().service("  Order-Service ").type("db_pool_exhausted").build();

        assertThat(Fingerprint.of(messy)).isEqualTo(Fingerprint.of(normal));
    }

    @Test
    @DisplayName("другой сервис, окружение, тип или класс исключения → другой fingerprint")
    void differentKeyPartsGiveDifferentFingerprint() {
        Fingerprint base = Fingerprint.of(event().build());

        assertThat(Fingerprint.of(event().service("payment-service").build())).isNotEqualTo(base);
        assertThat(Fingerprint.of(event().environment("prod").build())).isNotEqualTo(base);
        assertThat(Fingerprint.of(event().type("HTTP_5XX").build())).isNotEqualTo(base);
        assertThat(Fingerprint.of(event().error(new ErrorDetails("java.io.IOException", null, null)).build()))
                .isNotEqualTo(base);
    }

    @Test
    @DisplayName("нет error и пустой className дают один и тот же fingerprint")
    void missingAndBlankClassNameAreEqual() {
        IncidentEvent noError = event().error(null).build();
        IncidentEvent blankClass = event().error(new ErrorDetails("  ", "msg", null)).build();

        assertThat(Fingerprint.of(noError)).isEqualTo(Fingerprint.of(blankClass));
    }

    @Test
    @DisplayName("значение — SHA-256 в hex (64 символа)")
    void valueIsSha256Hex() {
        assertThat(Fingerprint.of(event().build()).value()).matches("[0-9a-f]{64}");
    }
}
