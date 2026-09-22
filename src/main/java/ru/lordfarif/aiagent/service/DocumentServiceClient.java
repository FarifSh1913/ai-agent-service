package ru.lordfarif.aiagent.service;

import java.net.http.HttpClient;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import ru.lordfarif.aiagent.config.DocumentServiceProperties;

@Component
public class DocumentServiceClient {
    private static final Logger log = LoggerFactory.getLogger(DocumentServiceClient.class);
    private final DocumentServiceProperties properties;
    private final RestClient.Builder builder;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Autowired
    public DocumentServiceClient(DocumentServiceProperties properties) {
        this(properties, RestClient.builder().baseUrl(properties.getBaseUrl()));
    }

    DocumentServiceClient(DocumentServiceProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.builder = builder;
    }

    public String startApproval(long deadline) {
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(remaining(deadline));
        RestClient client = builder.clone().requestFactory(factory).build();
        String endpoint = properties.getBaseUrl().replaceAll("/+$", "") + "/api/v1/processes/document-approval";
        log.info("Starting document approval process: url={}, body={{applicationId=APP-FENCE-001, applicantName=Николай Николаевич, documentType=FENCE_APPROVAL}}", endpoint);
        try {
            JsonNode response = client.post().uri("/api/v1/processes/document-approval")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("applicationId", "APP-FENCE-001", "applicantName", "Николай Николаевич",
                            "documentType", "FENCE_APPROVAL"))
                    .retrieve().body(JsonNode.class);
            remaining(deadline);
            if (response == null || !response.isObject()) throw invalidResponse();
            JsonNode key = response.path("processInstanceKey");
            if (key.isMissingNode() || key.isNull() || key.asString().isBlank()) throw invalidResponse();
            log.info("Document approval process started: processInstanceKey={}", safeKey(key.asString()));
            return key.asString();
        } catch (RestClientResponseException exception) {
            log.warn("Document service HTTP failure: status={}, body={}, exceptionClass={}, rootCause={}",
                    exception.getStatusCode().value(), safeBody(exception.getResponseBodyAsString()),
                    exception.getClass().getName(), rootCause(exception).getClass().getName());
            throw failed();
        } catch (ResourceAccessException exception) {
            log.warn("Document service transport failure: exceptionClass={}, rootCause={}, rootMessage={}",
                    exception.getClass().getName(), rootCause(exception).getClass().getName(), safeMessage(rootCause(exception)));
            throw unavailable();
        } catch (AgentException exception) {
            throw exception;
        } catch (RestClientException exception) {
            log.warn("Document service client failure: exceptionClass={}, rootCause={}, rootMessage={}",
                    exception.getClass().getName(), rootCause(exception).getClass().getName(), safeMessage(rootCause(exception)));
            throw isTransportFailure(exception) ? unavailable() : invalidResponse();
        }
    }

    private static Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new AgentException(HttpStatus.GATEWAY_TIMEOUT, "OPENAI_TIMEOUT", "OpenAI request timed out.");
        return Duration.ofNanos(nanos);
    }

    private static AgentException failed() {
        return new AgentException(HttpStatus.BAD_GATEWAY, "CAMUNDA_START_FAILED", "Document approval process could not be started.");
    }

    private static AgentException unavailable() {
        return new AgentException(HttpStatus.SERVICE_UNAVAILABLE, "DOCUMENT_SERVICE_UNAVAILABLE", "Document service is unavailable.");
    }

    private static AgentException invalidResponse() {
        return new AgentException(HttpStatus.BAD_GATEWAY, "CAMUNDA_RESPONSE_INVALID", "Document service returned an invalid process response.");
    }

    private static String safeKey(String value) {
        return value.matches("[A-Za-z0-9_-]{1,128}") ? value : "redacted";
    }

    private static Throwable rootCause(Throwable exception) {
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root;
    }

    private static boolean isTransportFailure(Throwable exception) {
        Throwable root = rootCause(exception);
        return root instanceof IOException || root instanceof TimeoutException || root instanceof UnknownHostException
                || root instanceof ConnectException || root instanceof SocketException || root instanceof SocketTimeoutException;
    }

    private static String safeMessage(Throwable exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "not_available";
        return message.length() > 300 ? message.substring(0, 300) + "…" : message;
    }

    private static String safeBody(String body) {
        if (body == null || body.isBlank()) return "<empty>";
        return body.length() > 1000 ? body.substring(0, 1000) + "…" : body;
    }
}
