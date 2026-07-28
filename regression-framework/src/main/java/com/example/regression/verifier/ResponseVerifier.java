package com.example.regression.verifier;

import com.example.regression.context.TestStepContext;
import com.example.regression.model.VerificationResult;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class ResponseVerifier implements Verifier {

    @Override
    public String supportedVerifierType() {
        return "jsonPath";
    }

    @Override
    public VerificationResult verify(Map<String, Object> actual, Map<String, Object> expected, TestStepContext context) {
        return switch ((String) expected.getOrDefault("type", "jsonPath")) {
            case "statusCode" -> verifyStatusCode(actual, expected);
            case "bodyContains" -> verifyBodyContains(actual, expected);
            default -> verifyJsonPath(actual, expected);
        };
    }

    private VerificationResult verifyJsonPath(Map<String, Object> actual, Map<String, Object> expected) {
        var path = (String) expected.get("path");
        var expectedValue = expected.get("value");
        var actualValue = resolveJsonPath(actual, path);
        var passed = expectedValue.equals(actualValue);
        return new VerificationResult(null, "step", null, "jsonPath", passed,
                Map.of("value", actualValue),
                Map.of("value", expectedValue),
                passed ? "OK" : "Expected '%s' but got '%s' at path '%s'".formatted(expectedValue, actualValue, path),
                null);
    }

    private VerificationResult verifyStatusCode(Map<String, Object> actual, Map<String, Object> expected) {
        var expectedCode = ((Number) expected.get("value")).intValue();
        var actualCode = ((Number) actual.getOrDefault("statusCode", 0)).intValue();
        var passed = actualCode == expectedCode;
        return new VerificationResult(null, "step", null, "statusCode", passed,
                Map.of("statusCode", actualCode),
                Map.of("statusCode", expectedCode),
                passed ? "OK" : "Expected status %d but got %d".formatted(expectedCode, actualCode),
                null);
    }

    private VerificationResult verifyBodyContains(Map<String, Object> actual, Map<String, Object> expected) {
        var substring = (String) expected.get("value");
        var content = actual.getOrDefault("content", actual.getOrDefault("payload", "")).toString();
        var passed = content.contains(substring);
        return new VerificationResult(null, "step", null, "bodyContains", passed,
                Map.of("content", content),
                Map.of("contains", substring),
                passed ? "OK" : "Expected content to contain '%s'".formatted(substring),
                null);
    }

    @SuppressWarnings("unchecked")
    private Object resolveJsonPath(Map<String, Object> map, String path) {
        if (path == null || path.isEmpty() || path.equals("$")) return map;
        String key = path.startsWith("$.") ? path.substring(2) : path;
        String[] parts = key.split("\\.");
        Object current = map;
        for (String part : parts) {
            if (current instanceof Map<?, ?> m) {
                current = ((Map<String, Object>) m).get(part);
            } else {
                return null;
            }
        }
        return current;
    }
}
