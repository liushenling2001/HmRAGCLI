package com.hmrag.backend.service;

public class TaskCancelledException extends RuntimeException {

    public TaskCancelledException(String message) {
        super(message);
    }
}
