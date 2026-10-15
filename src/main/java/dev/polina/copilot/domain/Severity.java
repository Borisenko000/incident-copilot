package dev.polina.copilot.domain;

/** Важность инцидента. Порядок констант — от низкой к высокой, на нём строится сравнение. */
public enum Severity {
    LOW, MEDIUM, HIGH, CRITICAL
}
