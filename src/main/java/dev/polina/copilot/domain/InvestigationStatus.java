package dev.polina.copilot.domain;

/** Статус расследования; совпадает с CHECK в ops.investigations. */
public enum InvestigationStatus {
    QUEUED, RUNNING, DIAGNOSED, INCONCLUSIVE, FAILED
}
