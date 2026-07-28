package com.example.regression.verifier;

import com.example.regression.context.TestStepContext;
import com.example.regression.model.VerificationResult;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsRequest;

import java.time.Instant;
import java.util.Map;

@Component
public class LambdaVerifier implements Verifier {

    private final CloudWatchLogsClient cloudWatchLogsClient;

    public LambdaVerifier(CloudWatchLogsClient cloudWatchLogsClient) {
        this.cloudWatchLogsClient = cloudWatchLogsClient;
    }

    @Override
    public String supportedVerifierType() {
        return "logContains";
    }

    @Override
    public VerificationResult verify(Map<String, Object> actual, Map<String, Object> expected, TestStepContext context) {
        return switch ((String) expected.getOrDefault("type", "logContains")) {
            case "durationWithin" -> verifyDuration(actual, expected);
            default -> verifyLogContains(expected);
        };
    }

    private VerificationResult verifyLogContains(Map<String, Object> expected) {
        var logGroupName = (String) expected.get("logGroupName");
        var pattern = (String) expected.get("pattern");
        var request = FilterLogEventsRequest.builder()
                .logGroupName(logGroupName)
                .filterPattern(pattern)
                .startTime(Instant.now().minusSeconds(3600).toEpochMilli())
                .endTime(Instant.now().toEpochMilli())
                .build();
        var response = cloudWatchLogsClient.filterLogEvents(request);
        var passed = !response.events().isEmpty();
        return new VerificationResult(null, "step", null, "logContains", passed,
                Map.of("matches", response.events().size()),
                Map.of("pattern", pattern),
                passed ? "OK" : "No log events matching '%s' found in %s".formatted(pattern, logGroupName),
                null);
    }

    private VerificationResult verifyDuration(Map<String, Object> actual, Map<String, Object> expected) {
        var maxDurationMs = ((Number) expected.get("maxDurationMs")).longValue();
        var actualDuration = actual.containsKey("durationMs")
                ? ((Number) actual.get("durationMs")).longValue() : 0L;
        var passed = actualDuration <= maxDurationMs;
        return new VerificationResult(null, "step", null, "durationWithin", passed,
                Map.of("durationMs", actualDuration),
                Map.of("maxDurationMs", maxDurationMs),
                passed ? "OK" : "Duration %dms exceeded max %dms".formatted(actualDuration, maxDurationMs),
                null);
    }
}
