package com.example.regression.verifier;

import com.example.regression.exception.VerificationFailedException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class VerifierRegistry {

    private final Map<String, Verifier> verifiers = new HashMap<>();

    public VerifierRegistry(List<Verifier> verifierList) {
        for (var verifier : verifierList) {
            var existing = verifiers.putIfAbsent(verifier.supportedVerifierType(), verifier);
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate verifier for type: " + verifier.supportedVerifierType());
            }
        }
    }

    public Verifier getVerifier(String verifierType) {
        var verifier = verifiers.get(verifierType);
        if (verifier == null) {
            throw new VerificationFailedException("No verifier for type: " + verifierType);
        }
        return verifier;
    }

    public Map<String, Verifier> allVerifiers() {
        return Map.copyOf(verifiers);
    }
}
