package com.example.regression.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record VerificationResult(
    UUID id,
    String scope,
    UUID scopeId,
    String verifierType,
    Boolean passed,
    Map<String, Object> actual,
    Map<String, Object> expected,
    String message,
    Instant createdAt
) {}
