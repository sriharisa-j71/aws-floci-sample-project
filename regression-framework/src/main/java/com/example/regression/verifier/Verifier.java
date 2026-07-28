package com.example.regression.verifier;

import com.example.regression.context.TestStepContext;
import com.example.regression.model.VerificationResult;

import java.util.Map;

public interface Verifier {
    String supportedVerifierType();
    VerificationResult verify(Map<String, Object> actual, Map<String, Object> expected, TestStepContext context);
}
