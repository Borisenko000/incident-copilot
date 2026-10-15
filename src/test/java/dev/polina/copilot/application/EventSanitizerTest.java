package dev.polina.copilot.application;

import static dev.polina.copilot.domain.TestEvents.event;
import static org.assertj.core.api.Assertions.assertThat;

import dev.polina.copilot.domain.IncidentEvent;
import dev.polina.copilot.domain.IncidentEvent.ErrorDetails;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EventSanitizerTest {

    private final EventSanitizer sanitizer = new EventSanitizer();

    // ---------- mask(String): правила для свободного текста ----------

    @Test
    @DisplayName("текст без секретов не меняется")
    void leavesPlainTextUnchanged() {
        String text = "HikariPool-1 - Connection is not available, request timed out after 30000ms";

        assertThat(sanitizer.mask(text)).isEqualTo(text);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
            "password=hunter2                | password=***",
            "login failed, pwd: hunter2      | login failed, pwd: ***",
            "{\"token\":\"abc123\"}          | {\"token\":\"***\"}",
            "api_key=abc123&x=1              | api_key=***&x=1",
            "apiKey: abc123                  | apiKey: ***",
            "access_token=abc123             | access_token=***",
            "client_secret=abc123            | client_secret=***",
            "PASSWORD = hunter2              | PASSWORD = ***",
            "dbPassword=hunter2              | dbPassword=***",
            "clientSecret: s3cr3t            | clientSecret: ***",
            "refreshToken=abc123             | refreshToken=***",
            "credentials=app:hunter2         | credentials=***",
            "Cookie: SESSION=abc123          | Cookie: ***"
    })
    @DisplayName("секрет в формате ключ=значение маскируется, ключ остаётся")
    void masksKeyValueSecrets(String input, String expected) {
        assertThat(sanitizer.mask(input)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig | Authorization: Bearer ***",
            "Authorization: Basic dXNlcjpwYXNz                     | Authorization: Basic ***",
            "sent Bearer eyJhbGciOiJIUzI1NiJ9 to gateway           | sent Bearer *** to gateway"
    })
    @DisplayName("заголовок Authorization и Bearer-токен маскируются")
    void masksAuthorizationTokens(String input, String expected) {
        assertThat(sanitizer.mask(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("пароль в URL / строке подключения маскируется, логин и хост остаются")
    void masksCredentialsInUrl() {
        String masked = sanitizer.mask("Cannot connect to jdbc:postgresql://app:s3cret@db:5432/orders");

        assertThat(masked).isEqualTo("Cannot connect to jdbc:postgresql://app:***@db:5432/orders");
    }

    @Test
    @DisplayName("password в query строки JDBC маскируется")
    void masksPasswordInJdbcQuery() {
        String masked = sanitizer.mask("jdbc:postgresql://db/orders?user=app&password=s3cret");

        assertThat(masked).isEqualTo("jdbc:postgresql://db/orders?user=app&password=***");
    }

    @Test
    @DisplayName("значение в кавычках маскируется целиком, даже с пробелами")
    void masksQuotedValueWithSpaces() {
        assertThat(sanitizer.mask("{\"password\": \"correct horse battery staple\", \"user\": \"app\"}"))
                .isEqualTo("{\"password\": \"***\", \"user\": \"app\"}");
        assertThat(sanitizer.mask("secret='correct horse battery'"))
                .isEqualTo("secret='***'");
    }

    @Test
    @DisplayName("e-mail маскируется как PII")
    void masksEmail() {
        assertThat(sanitizer.mask("user john.doe@example.com is locked"))
                .isEqualTo("user ***@*** is locked");
    }

    @Test
    @DisplayName("повторная маскировка ничего не меняет")
    void maskIsIdempotent() {
        String once = sanitizer.mask("postgresql://app:s3cret@db password=x Authorization: Bearer abcdefghij a@b.io");

        assertThat(sanitizer.mask(once)).isEqualTo(once);
    }

    @Test
    @DisplayName("null остаётся null")
    void nullStaysNull() {
        assertThat(sanitizer.mask(null)).isNull();
    }

    // ---------- sanitize(IncidentEvent): обход полей события ----------

    @Test
    @DisplayName("маскируется весь свободный текст события, идентификаторы не трогаются")
    void sanitizesAllFreeTextFields() {
        IncidentEvent event = event()
                .summary("password=hunter2")
                .error(new ErrorDetails("java.sql.SQLException", "token=abc123", "at db(postgresql://app:s3cret@db)"))
                .correlation(Map.of("traceId", "trace-1", "note", "secret=xyz"))
                .technology(Map.of("db", "postgresql://app:s3cret@db"))
                .build();

        IncidentEvent clean = sanitizer.sanitize(event);

        assertThat(clean.summary()).isEqualTo("password=***");
        assertThat(clean.error().className()).isEqualTo("java.sql.SQLException");
        assertThat(clean.error().message()).isEqualTo("token=***");
        assertThat(clean.error().stackTrace()).isEqualTo("at db(postgresql://app:***@db)");
        assertThat(clean.correlation()).containsEntry("traceId", "trace-1").containsEntry("note", "secret=***");
        assertThat(clean.technology()).containsEntry("db", "postgresql://app:***@db");
        assertThat(clean)
                .extracting(IncidentEvent::eventId, IncidentEvent::source, IncidentEvent::service,
                        IncidentEvent::environment, IncidentEvent::type, IncidentEvent::severity,
                        IncidentEvent::occurredAt)
                .containsExactly(event.eventId(), event.source(), event.service(),
                        event.environment(), event.type(), event.severity(), event.occurredAt());
    }

    @Test
    @DisplayName("значение атрибута маскируется целиком, если имя ключа секретное — и во вложенных объектах")
    void masksAttributesBySecretKeyName() {
        IncidentEvent event = event().attributes(Map.of(
                "password", "hunter2",
                "dbPassword", 12345,
                "topic", "orders",
                "attempts", 3,
                "auth", Map.of("accessToken", "abc"),
                "hosts", List.of("postgresql://app:s3cret@db1")
        )).build();

        Map<String, Object> attrs = sanitizer.sanitize(event).attributes();

        assertThat(attrs)
                .containsEntry("password", "***")
                .containsEntry("dbPassword", "***")
                .containsEntry("topic", "orders")
                .containsEntry("attempts", 3)
                .containsEntry("auth", Map.of("accessToken", "***"))
                .containsEntry("hosts", List.of("postgresql://app:***@db1"));
    }

    @Test
    @DisplayName("секретный ключ со списком или объектом — значение скрывается целиком")
    void masksWholeStructuredValueUnderSecretKey() {
        IncidentEvent event = event().attributes(Map.of(
                "apiKeys", List.of("sk-live-123"),
                "token", Map.of("value", "abc123")
        )).build();

        assertThat(sanitizer.sanitize(event).attributes())
                .containsEntry("apiKeys", "***")
                .containsEntry("token", "***");
    }

    @Test
    @DisplayName("ключи Authorization, Cookie, credentials в атрибутах маскируются")
    void masksHeaderLikeAttributeKeys() {
        IncidentEvent event = event().attributes(Map.of(
                "Authorization", "Basic dXNlcjpwYXNz",
                "Cookie", "SESSION=abc",
                "credentials", "app:hunter2"
        )).build();

        assertThat(sanitizer.sanitize(event).attributes())
                .containsEntry("Authorization", "***")
                .containsEntry("Cookie", "***")
                .containsEntry("credentials", "***");
    }

    @Test
    @DisplayName("sanitizeManual маскирует и type/service/environment; обычный sanitize их не трогает")
    void sanitizeManualMasksHumanTypedIdentifiers() {
        IncidentEvent event = event().type("token=abc").service("svc password=x").environment("staging").build();

        IncidentEvent manual = sanitizer.sanitizeManual(event);
        IncidentEvent auto = sanitizer.sanitize(event);

        assertThat(manual.type()).isEqualTo("token=***");
        assertThat(manual.service()).isEqualTo("svc password=***");
        assertThat(manual.environment()).isEqualTo("staging");
        assertThat(auto.type()).isEqualTo("token=abc");
        assertThat(auto.service()).isEqualTo("svc password=x");
    }

    @Test
    @DisplayName("событие без error и словарей обрабатывается без ошибок")
    void handlesMissingOptionalParts() {
        IncidentEvent event = event().summary(null).error(null).attributes(null).build();

        IncidentEvent clean = sanitizer.sanitize(event);

        assertThat(clean.summary()).isNull();
        assertThat(clean.error()).isNull();
        assertThat(clean.attributes()).isEmpty();
    }
}
