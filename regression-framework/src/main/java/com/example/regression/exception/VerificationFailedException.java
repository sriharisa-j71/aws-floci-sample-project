package com.example.regression.exception;

public class VerificationFailedException extends RuntimeException {
    public VerificationFailedException(String message) { super(message); }
    public VerificationFailedException(String message, Throwable cause) { super(message, cause); }
}
