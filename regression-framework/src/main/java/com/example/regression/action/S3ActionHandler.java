package com.example.regression.action;

import com.example.regression.context.ContextResolver;
import com.example.regression.context.TestStepContext;
import com.example.regression.exception.ActionExecutionException;
import com.example.regression.model.Action;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class S3ActionHandler implements ActionHandler {

    private final S3Client s3Client;

    public S3ActionHandler(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    @Override
    public String supportedActionType() {
        return "s3:putObject";
    }

    @Override
    public Map<String, Object> handle(Action action, TestStepContext context, ContextResolver resolver) {
        return switch (action.actionType()) {
            case "s3:putObject" -> handlePutObject(action);
            case "s3:getObject" -> handleGetObject(action);
            case "s3:deleteObject" -> handleDeleteObject(action);
            case "s3:listObjects" -> handleListObjects(action);
            default -> throw new ActionExecutionException("Unsupported S3 action: " + action.actionType());
        };
    }

    private Map<String, Object> handlePutObject(Action action) {
        var params = action.params();
        var bucket = (String) params.get("bucket");
        var key = (String) params.get("key");
        var content = (String) params.getOrDefault("content", "");
        var request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        var response = s3Client.putObject(request, RequestBody.fromString(content));
        var result = new HashMap<String, Object>();
        result.put("eTag", response.eTag());
        result.put("versionId", response.versionId());
        result.put("statusCode", 200);
        return result;
    }

    private Map<String, Object> handleGetObject(Action action) {
        var params = action.params();
        var bucket = (String) params.get("bucket");
        var key = (String) params.get("key");
        var request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        var response = s3Client.getObjectAsBytes(request);
        var result = new HashMap<String, Object>();
        result.put("content", response.asUtf8String());
        result.put("contentType", response.response().contentType());
        result.put("contentLength", response.response().contentLength());
        result.put("statusCode", 200);
        return result;
    }

    private Map<String, Object> handleDeleteObject(Action action) {
        var params = action.params();
        var bucket = (String) params.get("bucket");
        var key = (String) params.get("key");
        var request = DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        s3Client.deleteObject(request);
        return Map.of("deleted", true, "statusCode", 204);
    }

    private Map<String, Object> handleListObjects(Action action) {
        var params = action.params();
        var bucket = (String) params.get("bucket");
        var prefix = (String) params.getOrDefault("prefix", "");
        var request = ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(prefix)
                .build();
        var response = s3Client.listObjectsV2(request);
        var keys = response.contents().stream()
                .map(S3Object::key)
                .collect(Collectors.toList());
        var result = new HashMap<String, Object>();
        result.put("keys", keys);
        result.put("keyCount", response.keyCount());
        result.put("statusCode", 200);
        return result;
    }
}
