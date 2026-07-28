package com.example.regression.verifier;

import com.example.regression.context.TestStepContext;
import com.example.regression.model.VerificationResult;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogGroupsRequest;

import java.time.Instant;
import java.util.Map;

@Component
public class CloudWatchVerifier implements Verifier {

    private final CloudWatchClient cloudWatchClient;
    private final CloudWatchLogsClient cloudWatchLogsClient;

    public CloudWatchVerifier(CloudWatchClient cloudWatchClient,
                               CloudWatchLogsClient cloudWatchLogsClient) {
        this.cloudWatchClient = cloudWatchClient;
        this.cloudWatchLogsClient = cloudWatchLogsClient;
    }

    @Override
    public String supportedVerifierType() {
        return "metricThreshold";
    }

    @Override
    public VerificationResult verify(Map<String, Object> actual, Map<String, Object> expected, TestStepContext context) {
        return switch ((String) expected.getOrDefault("type", "metricThreshold")) {
            case "logGroupExists" -> verifyLogGroupExists(expected);
            default -> verifyMetricThreshold(expected);
        };
    }

    private VerificationResult verifyMetricThreshold(Map<String, Object> expected) {
        var namespace = (String) expected.get("namespace");
        var metricName = (String) expected.get("metricName");
        var threshold = ((Number) expected.get("threshold")).doubleValue();
        var comparison = (String) expected.getOrDefault("comparison", "GE");
        var request = GetMetricDataRequest.builder()
                .metricDataQueries(MetricDataQuery.builder()
                        .id("m1")
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder()
                                        .namespace(namespace)
                                        .metricName(metricName)
                                        .build())
                                .period(300)
                                .stat("Average")
                                .build())
                        .build())
                .startTime(Instant.now().minusSeconds(3600))
                .endTime(Instant.now())
                .build();
        var response = cloudWatchClient.getMetricData(request);
        var values = response.metricDataResults().stream()
                .flatMap(r -> r.values().stream())
                .toList();
        var actualValue = values.isEmpty() ? 0.0 : values.getLast();
        boolean passed = switch (comparison) {
            case "GE" -> actualValue >= threshold;
            case "LE" -> actualValue <= threshold;
            case "GT" -> actualValue > threshold;
            case "LT" -> actualValue < threshold;
            case "EQ" -> actualValue == threshold;
            default -> false;
        };
        return new VerificationResult(null, "step", null, "metricThreshold", passed,
                Map.of("value", actualValue),
                Map.of("threshold", threshold, "comparison", comparison),
                passed ? "OK" : "Metric %s/%s value %.2f %s threshold %.2f".formatted(
                        namespace, metricName, actualValue, comparison, threshold),
                null);
    }

    private VerificationResult verifyLogGroupExists(Map<String, Object> expected) {
        var logGroupName = (String) expected.get("logGroupName");
        var response = cloudWatchLogsClient.describeLogGroups(
                DescribeLogGroupsRequest.builder()
                        .logGroupNamePrefix(logGroupName)
                        .build());
        var exists = response.logGroups().stream()
                .anyMatch(lg -> lg.logGroupName().equals(logGroupName));
        var shouldExist = (Boolean) expected.getOrDefault("exists", true);
        var passed = shouldExist ? exists : !exists;
        return new VerificationResult(null, "step", null, "logGroupExists", passed,
                Map.of("exists", exists),
                Map.of("logGroupName", logGroupName, "exists", shouldExist),
                passed ? "OK" : "Log group %s existence check failed".formatted(logGroupName),
                null);
    }
}
