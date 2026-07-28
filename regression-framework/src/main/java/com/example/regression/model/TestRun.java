package com.example.regression.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record TestRun(
    UUID runId,
    String suiteName,
    Integer suiteVersion,
    String status,
    Instant startTime,
    Instant endTime,
    Integer totalCases,
    Integer passedCases,
    Integer failedCases,
    Integer errorCases,
    Integer skippedCases,
    Long durationMs,
    Map<String, Object> tags,
    Map<String, Object> metadata,
    String recoveryNote
) {}
