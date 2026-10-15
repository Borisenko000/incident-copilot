package dev.polina.copilot.application;

import dev.polina.copilot.domain.IncidentEvent;
import dev.polina.copilot.domain.IncidentEvent.ErrorDetails;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Маскирует секреты и PII в свободном тексте события до сохранения и до отправки в LLM.
 * Идентификаторы (eventId, source, service, environment, type) не трогаются: на них
 * держатся идемпотентность и fingerprint. Регулярные выражения ловят типовые форматы,
 * а не все возможные: каждая новая находка — новое правило и тест.
 */
public final class EventSanitizer {

    static final String MASK = "***";

    /**
     * Имена секретов. Ищутся и внутри составных имён (dbPassword, refreshToken, client_secret):
     * лишняя маска дешевле утечки.
     */
    private static final String SECRET_KEYS =
            "password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|credentials?|cookie";

    /** Начало пары «ключ: значение» — ключ, необязательная кавычка после него, разделитель. */
    private static final String KEY_PREFIX = "(?i)((?:" + SECRET_KEYS + ")[\"']?\\s*[:=]\\s*";

    /** Значение без кавычек — до разделителя: пробела, запятой, точки с запятой, кавычки, '&'. */
    private static final String VALUE = "[^\\s,;\"'&]+";

    // Порядок важен: сначала пароль в URL, иначе правило e-mail съело бы "user:pass@host".
    private static final List<Rule> RULES = List.of(
            // scheme://user:pass@host → scheme://user:***@host
            new Rule("(\\b[a-zA-Z][a-zA-Z0-9+.-]*://[^:/\\s@]+:)[^@\\s/]+(@)", "$1" + MASK + "$2"),
            // Authorization: Bearer xxx / Basic xxx / просто значение
            new Rule("(?i)(authorization[\"']?\\s*[:=]\\s*[\"']?)((?:bearer|basic)\\s+)?" + VALUE, "$1$2" + MASK),
            // Bearer-токен вне заголовка
            new Rule("(?i)\\b(bearer)\\s+[A-Za-z0-9._~+/=-]{8,}", "$1 " + MASK),
            // "password": "фраза с пробелами" и 'в одинарных кавычках' — значение целиком, до закрывающей кавычки
            new Rule(KEY_PREFIX + "\")[^\"]*(\")", "$1" + MASK + "$2"),
            new Rule(KEY_PREFIX + "')[^']*(')", "$1" + MASK + "$2"),
            // password=x, apiKey: x, dbPassword=x — значение без кавычек
            new Rule(KEY_PREFIX + "[\"']?)" + VALUE, "$1" + MASK),
            // e-mail (PII)
            new Rule("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", MASK + "@" + MASK));

    /** Имя ключа в attributes/correlation/technology, значение которого скрывается целиком. */
    private static final Pattern SECRET_KEY_NAME = Pattern.compile("(?i).*(" + SECRET_KEYS + "|authorization).*");

    public IncidentEvent sanitize(IncidentEvent event) {
        return new IncidentEvent(
                event.eventId(), event.source(), event.occurredAt(),
                event.service(), event.environment(), event.type(), event.severity(),
                mask(event.summary()),
                sanitize(event.error()),
                sanitizeMap(event.correlation()),
                sanitizeMap(event.attributes()),
                sanitizeMap(event.technology()));
    }

    public String mask(String text) {
        if (text == null) {
            return null;
        }
        String result = text;
        for (Rule rule : RULES) {
            result = rule.pattern().matcher(result).replaceAll(rule.replacement());
        }
        return result;
    }

    private ErrorDetails sanitize(ErrorDetails error) {
        if (error == null) {
            return null;
        }
        return new ErrorDetails(error.className(), mask(error.message()), mask(error.stackTrace()));
    }

    private Map<String, Object> sanitizeMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            String name = String.valueOf(key);
            boolean secretKey = SECRET_KEY_NAME.matcher(name).matches();
            // по секретному ключу скрываем значение целиком — число, строку, список или объект
            result.put(name, secretKey && value != null ? MASK : sanitizeValue(value));
        });
        return result;
    }

    private Object sanitizeValue(Object value) {
        if (value instanceof String text) {
            return mask(text);
        }
        if (value instanceof Map<?, ?> nested) {
            return sanitizeMap(nested);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::sanitizeValue).toList();
        }
        return value; // числа, boolean, null
    }

    private record Rule(Pattern pattern, String replacement) {
        Rule(String regex, String replacement) {
            this(Pattern.compile(regex), replacement);
        }
    }
}
