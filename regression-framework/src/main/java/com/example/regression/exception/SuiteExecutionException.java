package com.example.regression.exception;

public class SuiteExecutionException extends RuntimeException {
    public SuiteExecutionException(String message) { super(message); }
    public SuiteExecutionException(String message, Throwable cause) { super(message, cause); }
}
