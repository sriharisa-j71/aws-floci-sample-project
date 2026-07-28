package com.example.regression.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record SuiteResult(
    UUID id,
    UUID runId,
    Map<String, Object> suiteSnapshot,
    Map<String, Object> lifecycleResults,
    String status,
    Integer totalSteps,
    Integer passedSteps,
    Integer failedSteps,
    Instant startTime,
    Instant endTime,
    Long durationMs,
    String errorMessage
) {}
