package com.example.regression.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record TestStepResult(
    UUID id,
    UUID caseResultId,
    String stepName,
    String status,
    String failureCategory,
    String actionType,
    Map<String, Object> actionParams,
    Map<String, Object> response,
    Instant startTime,
    Instant endTime,
    Long durationMs,
    Integer retryCount,
    String errorMessage,
    Map<String, Object> metadata
) {}
