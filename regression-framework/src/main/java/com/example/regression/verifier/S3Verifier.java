package com.example.regression.verifier;

import com.example.regression.context.TestStepContext;
import com.example.regression.model.VerificationResult;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;

import java.util.HashMap;
import java.util.Map;

@Component
public class S3Verifier implements Verifier {

    private final S3Client s3Client;

    public S3Verifier(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    @Override
    public String supportedVerifierType() {
        return "objectExists";
    }

    @Override
    public VerificationResult verify(Map<String, Object> actual, Map<String, Object> expected, TestStepContext context) {
        var bucket = (String) expected.get("bucket");
        var key = (String) expected.get("key");
        var exists = objectExists(bucket, key);
        var passed = Boolean.TRUE.equals(expected.getOrDefault("exists", true)) ? exists : !exists;
        return new VerificationResult(null, "step", null, "objectExists", passed,
                Map.of("exists", exists),
                Map.of("exists", expected.getOrDefault("exists", true)),
                passed ? "OK" : "S3 object s3://%s/%s existence check failed".formatted(bucket, key),
                null);
    }

    private boolean objectExists(String bucket, String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
