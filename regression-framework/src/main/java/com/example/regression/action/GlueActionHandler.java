package com.example.regression.action;

import com.example.regression.context.ContextResolver;
import com.example.regression.context.TestStepContext;
import com.example.regression.exception.ActionExecutionException;
import com.example.regression.model.Action;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.*;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class GlueActionHandler implements ActionHandler {

    private final GlueClient glueClient;

    public GlueActionHandler(GlueClient glueClient) {
        this.glueClient = glueClient;
    }

    @Override
    public String supportedActionType() {
        return "glue:startJobRun";
    }

    @Override
    public Map<String, Object> handle(Action action, TestStepContext context, ContextResolver resolver) {
        return switch (action.actionType()) {
            case "glue:startJobRun" -> handleStartJobRun(action);
            case "glue:getJobRun" -> handleGetJobRun(action);
            case "glue:listJobs" -> handleListJobs();
            default -> throw new ActionExecutionException("Unsupported Glue action: " + action.actionType());
        };
    }

    private Map<String, Object> handleStartJobRun(Action action) {
        var params = action.params();
        var jobName = (String) params.get("jobName");
        var request = StartJobRunRequest.builder()
                .jobName(jobName)
                .build();
        var response = glueClient.startJobRun(request);
        var result = new HashMap<String, Object>();
        result.put("jobRunId", response.jobRunId());
        result.put("jobName", jobName);
        result.put("statusCode", 200);
        return result;
    }

    private Map<String, Object> handleGetJobRun(Action action) {
        var params = action.params();
        var jobName = (String) params.get("jobName");
        var jobRunId = (String) params.get("jobRunId");
        var request = GetJobRunRequest.builder()
                .jobName(jobName)
                .runId(jobRunId)
                .build();
        var response = glueClient.getJobRun(request);
        var jobRun = response.jobRun();
        var result = new HashMap<String, Object>();
        result.put("jobRunId", jobRun.id());
        result.put("jobName", jobRun.jobName());
        result.put("jobRunState", jobRun.jobRunStateAsString());
        result.put("startedOn", jobRun.startedOn() != null ? jobRun.startedOn().toString() : null);
        result.put("completedOn", jobRun.completedOn() != null ? jobRun.completedOn().toString() : null);
        result.put("errorMessage", jobRun.errorMessage());
        result.put("executionTime", jobRun.executionTime());
        result.put("statusCode", 200);
        return result;
    }

    private Map<String, Object> handleListJobs() {
        var response = glueClient.listJobs(ListJobsRequest.builder().build());
        var result = new HashMap<String, Object>();
        result.put("jobNames", response.jobNames());
        result.put("statusCode", 200);
        return result;
    }
}
