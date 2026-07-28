package com.example.regression.exception;

public class ActionExecutionException extends RuntimeException {
    public ActionExecutionException(String message) { super(message); }
    public ActionExecutionException(String message, Throwable cause) { super(message, cause); }
}
