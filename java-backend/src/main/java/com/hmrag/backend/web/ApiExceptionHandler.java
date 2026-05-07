package com.hmrag.backend.web;

import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import java.io.IOException;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(EntityNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleNotFound(EntityNotFoundException ex) {
        log.warn("API 404: {}", ex.getMessage());
        return Map.of("detail", ex.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadRequest(Exception ex) {
        log.warn("API 400: {}", ex.getMessage(), ex);
        return Map.of("detail", ex.getMessage());
    }

    @ExceptionHandler(DataAccessException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, String> handleDataAccess(DataAccessException ex) {
        String message = ex.getMostSpecificCause() != null ? ex.getMostSpecificCause().getMessage() : ex.getMessage();
        log.error("API database error: {}", message, ex);
        return Map.of("detail", message == null ? "Database query failed" : message);
    }

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    @ResponseStatus(HttpStatus.GATEWAY_TIMEOUT)
    public Map<String, String> handleAsyncTimeout(AsyncRequestTimeoutException ex) {
        log.warn("API async timeout: {}", ex.getMessage(), ex);
        return Map.of("detail", "请求执行超时，任务已停止等待，请稍后重试");
    }

    @ExceptionHandler({AsyncRequestNotUsableException.class})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void handleAsyncRequestNotUsable(Exception ex) {
        log.warn("API client disconnected during async response: {}", ex.getMessage());
    }

    @ExceptionHandler(TaskRejectedException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> handleTaskRejected(TaskRejectedException ex) {
        log.warn("API task rejected: {}", ex.getMessage(), ex);
        return Map.of("detail", "系统忙，维护任务队列已满，请稍后重试");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, String> handleServerError(Exception ex) {
        if (isClientAbort(ex)) {
            log.warn("API client disconnected: {}", ex.getMessage());
            return Map.of("detail", "client_disconnected");
        }
        log.error("API server error: {}", ex.getMessage(), ex);
        return Map.of("detail", ex.getMessage() == null ? "Internal server error" : ex.getMessage());
    }

    private boolean isClientAbort(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof AsyncRequestNotUsableException) {
                return true;
            }
            String className = current.getClass().getName();
            if ("org.apache.catalina.connector.ClientAbortException".equals(className)) {
                return true;
            }
            if (current instanceof IOException) {
                String message = String.valueOf(current.getMessage()).toLowerCase();
                if (message.contains("broken pipe")
                        || message.contains("connection reset")
                        || message.contains("software caused connection abort")
                        || message.contains("你的主机中的软件中止了一个已建立的连接")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
