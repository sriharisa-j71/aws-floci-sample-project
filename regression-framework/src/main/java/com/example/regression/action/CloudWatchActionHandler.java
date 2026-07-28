package com.example.regression.action;

import com.example.regression.context.ContextResolver;
import com.example.regression.context.TestStepContext;
import com.example.regression.exception.ActionExecutionException;
import com.example.regression.model.Action;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class CloudWatchActionHandler implements ActionHandler {

    private final CloudWatchClient cloudWatchClient;
    private final CloudWatchLogsClient cloudWatchLogsClient;

    public CloudWatchActionHandler(CloudWatchClient cloudWatchClient,
                                    CloudWatchLogsClient cloudWatchLogsClient) {
        this.cloudWatchClient = cloudWatchClient;
        this.cloudWatchLogsClient = cloudWatchLogsClient;
    }

    @Override
    public String supportedActionType() {
        return "cloudwatch:putMetricData";
    }

    @Override
    public Map<String, Object> handle(Action action, TestStepContext context, ContextResolver resolver) {
        return switch (action.actionType()) {
            case "cloudwatch:putMetricData" -> handlePutMetricData(action);
            case "cloudwatch:getMetricData" -> handleGetMetricData(action);
            case "logs:describeLogGroups" -> handleDescribeLogGroups(action);
            case "logs:filterLogEvents" -> handleFilterLogEvents(action);
            default -> throw new ActionExecutionException("Unsupported CloudWatch action: " + action.actionType());
        };
    }

    private Map<String, Object> handlePutMetricData(Action action) {
        var params = action.params();
        var namespace = (String) params.get("namespace");
        var metricName = (String) params.get("metricName");
        var value = ((Number) params.getOrDefault("value", 0)).doubleValue();
        var unit = (String) params.getOrDefault("unit", "None");
        var request = PutMetricDataRequest.builder()
                .namespace(namespace)
                .metricData(List.of(MetricDatum.builder()
                        .metricName(metricName)
                        .value(value)
                        .unit(unit)
                        .timestamp(Instant.now())
                        .build()))
                .build();
        cloudWatchClient.putMetricData(request);
        return Map.of("statusCode", 200);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleGetMetricData(Action action) {
        var params = action.params();
        var namespace = (String) params.get("namespace");
        var metricName = (String) params.get("metricName");
        var period = ((Number) params.getOrDefault("period", 300)).intValue();
        var startTime = Instant.now().minusSeconds(3600);
        var endTime = Instant.now();
        if (params.containsKey("startTime")) {
            startTime = Instant.parse((String) params.get("startTime"));
        }
        if (params.containsKey("endTime")) {
            endTime = Instant.parse((String) params.get("endTime"));
        }
        var request = GetMetricDataRequest.builder()
                .metricDataQueries(MetricDataQuery.builder()
                        .id("m1")
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder()
                                        .namespace(namespace)
                                        .metricName(metricName)
                                        .build())
                                .period(period)
                                .stat("Average")
                                .build())
                        .build())
                .startTime(startTime)
                .endTime(endTime)
                .build();
        var response = cloudWatchClient.getMetricData(request);
        var result = new HashMap<String, Object>();
        var values = response.metricDataResults().stream()
                .flatMap(r -> r.values().stream())
                .collect(Collectors.toList());
        result.put("values", values);
        result.put("statusCode", 200);
        return result;
    }

    private Map<String, Object> handleDescribeLogGroups(Action action) {
        var params = action.params();
        var prefix = (String) params.getOrDefault("logGroupPrefix", "");
        var request = DescribeLogGroupsRequest.builder()
                .logGroupNamePrefix(prefix)
                .build();
        var response = cloudWatchLogsClient.describeLogGroups(request);
        var logGroups = response.logGroups().stream()
                .map(lg -> Map.of(
                        "logGroupName", lg.logGroupName(),
                        "creationTime", lg.creationTime(),
                        "storedBytes", lg.storedBytes()
                ))
                .collect(Collectors.toList());
        var result = new HashMap<String, Object>();
        result.put("logGroups", logGroups);
        result.put("statusCode", 200);
        return result;
    }

    private Map<String, Object> handleFilterLogEvents(Action action) {
        var params = action.params();
        var logGroupName = (String) params.get("logGroupName");
        var filterPattern = (String) params.getOrDefault("filterPattern", "");
        var startTime = Instant.now().minusSeconds(3600);
        var endTime = Instant.now();
        if (params.containsKey("startTime")) {
            startTime = Instant.parse((String) params.get("startTime"));
        }
        if (params.containsKey("endTime")) {
            endTime = Instant.parse((String) params.get("endTime"));
        }
        var request = FilterLogEventsRequest.builder()
                .logGroupName(logGroupName)
                .filterPattern(filterPattern)
                .startTime(startTime.toEpochMilli())
                .endTime(endTime.toEpochMilli())
                .build();
        var response = cloudWatchLogsClient.filterLogEvents(request);
        var events = response.events().stream()
                .map(e -> Map.of(
                        "message", e.message(),
                        "timestamp", e.timestamp(),
                        "logStreamName", e.logStreamName()
                ))
                .collect(Collectors.toList());
        var result = new HashMap<String, Object>();
        result.put("events", events);
        result.put("count", events.size());
        result.put("statusCode", 200);
        return result;
    }
}
