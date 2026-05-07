package com.hmrag.backend.service;

public class ManualApprovalRequiredException extends RuntimeException {

    public ManualApprovalRequiredException(String message) {
        super(message);
    }
}
