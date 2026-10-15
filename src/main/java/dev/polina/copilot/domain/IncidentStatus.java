package dev.polina.copilot.domain;

/** Статус инцидента; совпадает с CHECK в ops.incidents. */
public enum IncidentStatus {
    OPEN, RESOLVED, CLOSED
}
