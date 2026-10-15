package dev.polina.copilot.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * Ключ корреляции: одинаковая ошибка в одном сервисе и окружении даёт одинаковый fingerprint.
 * Детерминированный, без LLM (README §7.3): SHA-256 от {@code type|className|service|environment}.
 */
public record Fingerprint(String value) {

    private static final String MISSING = "-";

    public Fingerprint {
        Objects.requireNonNull(value, "value");
    }

    public static Fingerprint of(IncidentEvent event) {
        String className = event.error() == null ? null : event.error().className();
        String key = String.join("|",
                normalize(event.type()), normalize(className), normalize(event.service()), normalize(event.environment()));
        return new Fingerprint(sha256Hex(key));
    }

    /**
     * Ручной ввод в корреляции не участвует ни в одну сторону: ключ уникален по eventId,
     * поэтому ни ручной ввод не прилипнет к авто-инциденту, ни авто-событие — к ручному.
     */
    public static Fingerprint forManual(IncidentEvent event) {
        return new Fingerprint(sha256Hex("manual|" + event.eventId()));
    }

    private static String normalize(String part) {
        if (part == null || part.isBlank()) {
            return MISSING;
        }
        return part.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String sha256Hex(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 обязан быть в любой JVM", e);
        }
    }
}
