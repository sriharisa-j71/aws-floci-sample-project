package com.example.regression.model;

import java.util.Map;

public record Verification(
    String verifierType,
    Map<String, Object> expected
) {}
