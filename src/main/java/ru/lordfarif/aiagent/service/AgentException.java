package ru.lordfarif.aiagent.service;

import org.springframework.http.HttpStatus;

public class AgentException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public AgentException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}
