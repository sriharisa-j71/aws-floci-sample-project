package com.example.regression.verifier;

import com.example.regression.context.TestStepContext;
import com.example.regression.model.VerificationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VerifierRegistryTest {

    @Test
    void registersVerifiersFromBeanList() {
        var verifier = new TestVerifier();
        var registry = new VerifierRegistry(List.of(verifier));
        assertNotNull(registry.getVerifier("test:verify"));
    }

    @Test
    void duplicateVerifierThrows() {
        var verifier = new TestVerifier();
        assertThrows(IllegalStateException.class, () -> new VerifierRegistry(List.of(verifier, verifier)));
    }

    @Test
    void unknownVerifierThrows() {
        var registry = new VerifierRegistry(List.of());
        assertThrows(RuntimeException.class, () -> registry.getVerifier("unknown:type"));
    }

    static class TestVerifier implements Verifier {
        @Override
        public String supportedVerifierType() { return "test:verify"; }

        @Override
        public VerificationResult verify(Map<String, Object> actual, Map<String, Object> expected, TestStepContext context) {
            return new VerificationResult(null, "step", null, "test:verify", true, actual, expected, "OK", null);
        }
    }
}
