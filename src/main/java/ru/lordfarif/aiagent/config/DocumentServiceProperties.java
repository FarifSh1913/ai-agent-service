package ru.lordfarif.aiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "document-service")
public class DocumentServiceProperties {
    private String baseUrl = "http://localhost:8081";

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
}
