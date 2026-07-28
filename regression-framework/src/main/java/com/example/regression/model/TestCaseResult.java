package com.example.regression.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record TestCaseResult(
    UUID id,
    UUID runId,
    String caseName,
    String status,
    Map<String, Object> dataRow,
    Instant startTime,
    Instant endTime,
    Long durationMs,
    Integer totalSteps,
    Integer passedSteps,
    Integer failedSteps,
    String errorMessage,
    Map<String, Object> metadata
) {}
