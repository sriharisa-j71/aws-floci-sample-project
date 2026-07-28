package com.example.regression.verifier;

import com.example.regression.context.TestStepContext;
import com.example.regression.model.VerificationResult;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.GetJobRunRequest;
import software.amazon.awssdk.services.glue.model.JobRunState;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Component
public class GlueVerifier implements Verifier {

    private final GlueClient glueClient;

    public GlueVerifier(GlueClient glueClient) {
        this.glueClient = glueClient;
    }

    @Override
    public String supportedVerifierType() {
        return "jobStatus";
    }

    @Override
    public VerificationResult verify(Map<String, Object> actual, Map<String, Object> expected, TestStepContext context) {
        return switch ((String) expected.getOrDefault("type", "jobStatus")) {
            case "jobCompletedWithin" -> verifyJobCompletedWithin(expected);
            default -> verifyJobStatus(expected);
        };
    }

    private VerificationResult verifyJobStatus(Map<String, Object> expected) {
        var jobName = (String) expected.get("jobName");
        var jobRunId = (String) expected.get("jobRunId");
        var expectedStatus = (String) expected.get("expectedStatus");
        var request = GetJobRunRequest.builder()
                .jobName(jobName)
                .runId(jobRunId)
                .build();
        var response = glueClient.getJobRun(request);
        var actualStatus = response.jobRun().jobRunStateAsString();
        var passed = expectedStatus.equalsIgnoreCase(actualStatus);
        return new VerificationResult(null, "step", null, "jobStatus", passed,
                Map.of("status", actualStatus),
                Map.of("expectedStatus", expectedStatus),
                passed ? "OK" : "Expected job status '%s' but got '%s'".formatted(expectedStatus, actualStatus),
                null);
    }

    private VerificationResult verifyJobCompletedWithin(Map<String, Object> expected) {
        var jobName = (String) expected.get("jobName");
        var jobRunId = (String) expected.get("jobRunId");
        var maxDurationSeconds = ((Number) expected.get("maxDurationSeconds")).longValue();
        var startTime = Instant.now();
        JobRunState state;
        do {
            var response = glueClient.getJobRun(
                    GetJobRunRequest.builder().jobName(jobName).runId(jobRunId).build());
            state = response.jobRun().jobRunState();
            if (Duration.between(startTime, Instant.now()).getSeconds() > maxDurationSeconds) {
                return new VerificationResult(null, "step", null, "jobCompletedWithin", false,
                        Map.of("state", stateAsString(state), "duration", Duration.between(startTime, Instant.now()).getSeconds()),
                        Map.of("maxDurationSeconds", maxDurationSeconds),
                        "Job did not complete within %d seconds".formatted(maxDurationSeconds),
                        null);
            }
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        } while (state != JobRunState.SUCCEEDED && state != JobRunState.FAILED && state != JobRunState.STOPPED);
        var passed = state == JobRunState.SUCCEEDED;
        return new VerificationResult(null, "step", null, "jobCompletedWithin", passed,
                Map.of("state", stateAsString(state), "duration", Duration.between(startTime, Instant.now()).getSeconds()),
                Map.of("maxDurationSeconds", maxDurationSeconds),
                passed ? "OK" : "Job ended with state: " + stateAsString(state),
                null);
    }

    private String stateAsString(JobRunState state) {
        return state != null ? state.toString() : "UNKNOWN";
    }
}
