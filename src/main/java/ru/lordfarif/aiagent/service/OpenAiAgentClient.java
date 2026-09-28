package ru.lordfarif.aiagent.service;

import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.core.JacksonException;
import ru.lordfarif.aiagent.config.OpenAiProperties;
import ru.lordfarif.aiagent.dto.AgentChatResponse;
import ru.lordfarif.aiagent.dto.AgentImage;
import tools.jackson.databind.json.JsonMapper;

@Component
public class OpenAiAgentClient {
    private static final Logger log = LoggerFactory.getLogger(OpenAiAgentClient.class);
    private final OpenAiProperties properties;
    private ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final RestClient.Builder builder;
    private final HttpClient httpClient;
    private final Duration timeout;
    private final Duration pollInterval;
    private final OpenAiImageClient imageClient;
    private final DocumentServiceClient documentServiceClient;

    @Autowired
    public OpenAiAgentClient(OpenAiProperties properties, OpenAiImageClient imageClient, ObjectMapper objectMapper) {
        this(properties, RestClient.builder().baseUrl("https://api.openai.com/v1"),
                Duration.ofSeconds(180), Duration.ofMillis(500), imageClient,
                new DocumentServiceClient(new ru.lordfarif.aiagent.config.DocumentServiceProperties()));
        this.objectMapper = objectMapper;
    }

    OpenAiAgentClient(OpenAiProperties properties, RestClient.Builder builder,
                      Duration timeout, Duration pollInterval) {
        this(properties, builder, timeout, pollInterval,
                new OpenAiImageClient(properties, new ImageStorageService()),
                new DocumentServiceClient(new ru.lordfarif.aiagent.config.DocumentServiceProperties()));
    }

    OpenAiAgentClient(OpenAiProperties properties, RestClient.Builder builder,
                      Duration timeout, Duration pollInterval, OpenAiImageClient imageClient) {
        this(properties, builder, timeout, pollInterval, imageClient,
                new DocumentServiceClient(new ru.lordfarif.aiagent.config.DocumentServiceProperties()));
    }

    OpenAiAgentClient(OpenAiProperties properties, RestClient.Builder builder,
                      Duration timeout, Duration pollInterval, OpenAiImageClient imageClient,
                      DocumentServiceClient documentServiceClient) {
        this.imageClient = imageClient;
        this.documentServiceClient = documentServiceClient;
        this.properties = properties;
        this.builder = builder;
        this.timeout = timeout;
        this.pollInterval = pollInterval;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Runs a new, independent session and returns only its structured business result. */
    public <T> T runAgent(String agentId, Object input, Class<T> responseType) {
        return runAgent(agentId, input, responseType, (sessionId, result) -> result);
    }

    /** Supplies a log-safe session ID to business post-processing without exposing session metadata in the DTO. */
    public <T> T runAgent(String agentId, Object input, Class<T> responseType,
                          java.util.function.BiFunction<String, T, T> postProcessor) {
        requireConfiguration(properties.getApiKey(), "OPENAI_API_KEY");
        requireConfiguration(agentId, "OPENAI_NEARBY_PLACES_AGENT_ID");
        long deadline = System.nanoTime() + Duration.ofMillis(properties.getPolling().getTimeoutMs()).toNanos();
        String sessionId = null;
        try {
            String serializedInput = objectMapper.writeValueAsString(input);
            JsonNode session = call(deadline, client -> client.post().uri("/agents/sessions")
                    .body(Map.of("agent_id", agentId, "environment", Map.of("type", "none"),
                            "input", serializedInput, "stream", false)).retrieve().body(JsonNode.class));
            sessionId = requiredText(session, "id");
            while (true) {
                log.info("OpenAI structured agent poll: sessionId={}, status={}",
                        safeId(sessionId), safeLabel(session.path("status")));
                checkSession(session);
                if ("idle".equals(requiredText(session, "status"))) break;
                pause(deadline, Duration.ofMillis(properties.getPolling().getIntervalMs()));
                session = retrieveSession(sessionId, deadline);
            }
            var items = new ArrayList<JsonNode>();
            readItems(sessionId, deadline, items);
            JsonNode finalAnswer = null;
            for (JsonNode item : items) {
                if ("message".equals(item.path("type").asString())
                        && "assistant".equals(item.path("role").asString())
                        && "final_answer".equals(item.path("phase").asString())
                        && "completed".equals(item.path("status").asString())) {
                    finalAnswer = item;
                }
            }
            if (finalAnswer == null) {
                throw upstream("MISSING_FINAL_ANSWER", "Agent returned no completed final answer.");
            }
            JsonNode content = finalAnswer.path("content");
            if (!content.isArray() || content.isEmpty() || !content.path(0).path("text").isString()) {
                throw upstream("INVALID_AGENT_JSON", "Agent returned an invalid JSON result.");
            }
            try {
                // JsonNode already unescapes the outer JSON string. Parse exactly once into our DTO.
                T result = objectMapper.readerFor(responseType)
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readValue(content.path(0).path("text").asString());
                if (result == null) throw upstream("INVALID_AGENT_JSON", "Agent returned an invalid JSON result.");
                remaining(deadline);
                return postProcessor.apply(safeId(sessionId), result);
            } catch (JacksonException exception) {
                throw upstream("INVALID_AGENT_JSON", "Agent returned an invalid JSON result.");
            }
        } catch (AgentException exception) {
            log.warn("OpenAI structured agent failed: sessionId={}, code={}", safeId(sessionId), exception.getCode());
            throw exception;
        }
    }

    public AgentChatResponse chat(String message, String requestedSessionId) {
        requireConfiguration(properties.getApiKey(), "OPENAI_API_KEY");
        requireConfiguration(properties.getAgentId(), "OPENAI_AGENT_ID");
        if (requestedSessionId != null && requestedSessionId.isBlank()) {
            throw new AgentException(HttpStatus.BAD_REQUEST, "INVALID_SESSION_ID", "sessionId must be non-blank or null.");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        JsonNode session;
        String sessionId;
        String previousTurnId = null;
        if (requestedSessionId == null) {
            session = call(deadline, client -> client.post().uri("/agents/sessions")
                    .body(Map.of("agent_id", properties.getAgentId(), "environment", Map.of("type", "none"),
                            "input", message, "stream", false)).retrieve().body(JsonNode.class));
            sessionId = requiredText(session, "id");
        } else {
            sessionId = requestedSessionId;
            session = retrieveSession(sessionId, deadline);
            checkSession(session);
            if (!properties.getAgentId().equals(requiredText(session.path("agent"), "id"))) {
                throw new AgentException(HttpStatus.BAD_REQUEST, "SESSION_AGENT_MISMATCH",
                        "Session does not belong to the configured agent.");
            }
            // Sending to an active session steers its current turn instead of starting a new one.
            if (!"idle".equals(requiredText(session, "status"))) {
                throw new AgentException(HttpStatus.CONFLICT, "SESSION_BUSY", "Wait for the current session turn to finish.");
            }
            JsonNode previous = call(deadline, client -> client.get()
                    .uri("/agents/sessions/{id}/turns?order=desc&limit=100", sessionId)
                    .retrieve().body(JsonNode.class));
            for (JsonNode turn : data(previous)) {
                if (turn.path("subagent_id").isMissingNode() || turn.path("subagent_id").isNull()) {
                    previousTurnId = requiredText(turn, "id");
                    break;
                }
            }
            log.info("Continuation request: sessionId={}, previousTurnId={}", safeId(sessionId), safeId(previousTurnId));
            execute(deadline, client -> client.post().uri("/agents/sessions/{id}/events", sessionId)
                    .body(Map.of("events", List.of(Map.of("type", "agent.session.input.message",
                            "input", List.of(Map.of("role", "user", "content",
                                    List.of(Map.of("type", "input_text", "text", message))))))))
                    .retrieve().toBodilessEntity());
            log.info("Continuation event accepted: sessionId={}, previousTurnId={}, waitingForNewTurn=true",
                    safeId(sessionId), safeId(previousTurnId));
        }
        var images = new ArrayList<AgentImage>();
        String answer = awaitAnswer(sessionId, session, previousTurnId, deadline, images);
        return new AgentChatResponse(sessionId, answer, images);
    }

    private JsonNode retrieveSession(String sessionId, long deadline) {
        return call(deadline, client -> client.get().uri("/agents/sessions/{id}", sessionId)
                .retrieve().onStatus(status -> status.value() == 404, (request, response) -> {
                    throw new AgentException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "OpenAI session was not found.");
                }).body(JsonNode.class));
    }

    private String awaitAnswer(String sessionId, JsonNode session, String previousTurnId, long deadline, List<AgentImage> images) {
        String after = previousTurnId;
        String currentTurnId = null;
        var cursors = new HashSet<String>();
        var submittedCalls = new HashSet<ToolCall>();
        int iteration = 0;
        boolean newTurnLogged = false;
        while (true) {
            var snapshot = new PollSnapshot(++iteration, sessionId, session, currentTurnId);
            try {
                checkSession(session, true);
                String cursor = after;
                JsonNode turns = call(deadline, client -> client.get().uri(uri -> {
                    var path = uri.path("/agents/sessions/{id}/turns").queryParam("order", "asc").queryParam("limit", 100);
                    if (cursor != null) path.queryParam("after", cursor);
                    return path.build(sessionId);
                }).retrieve().body(JsonNode.class));
                for (JsonNode turn : data(turns)) {
                    if (!turn.path("subagent_id").isMissingNode() && !turn.path("subagent_id").isNull()) continue;
                    String turnId = requiredText(turn, "id");
                    if (turnId.equals(previousTurnId)) continue;
                    if (currentTurnId == null) currentTurnId = turnId;
                    if (!currentTurnId.equals(turnId)) continue;
                    snapshot.turnId = turnId;
                    snapshot.turn = turn;
                    boolean toolPending = hasFunctionAction(session, turnId);
                    boolean toolSubmitted = submittedCalls.stream().anyMatch(call -> call.turnId().equals(turnId));
                    if (!toolPending && !toolSubmitted && ("waiting".equals(turn.path("status").asString())
                            || "requires_action".equals(turn.path("status").asString()))) {
                        // The session snapshot can precede the function call observed in the turn snapshot.
                        session = retrieveSession(sessionId, deadline);
                        snapshot.session = session;
                        checkSession(session, true);
                        toolPending = hasFunctionAction(session, turnId);
                    }
                    checkTurn(turn, toolPending || toolSubmitted);
                    break;
                }
                if (currentTurnId == null && turns.path("has_more").asBoolean()) {
                    after = requiredText(turns, "last_id");
                    if (!cursors.add(after)) throw invalidResponse();
                    snapshot.reason = "NEXT_TURNS_PAGE";
                    continue;
                }
                boolean completed = snapshot.turn != null && "completed".equals(requiredText(snapshot.turn, "status"));
                boolean currentHasActions = currentTurnId != null && hasFunctionAction(session, currentTurnId);
                if (hasActions(session) && currentHasActions) {
                    if (completed) throw upstream("INVALID_AGENT_TOOL_CALL", "Pending tool call has no active root turn.");
                    handleToolCalls(sessionId, session, currentTurnId, deadline, submittedCalls, images);
                    snapshot.reason = "TOOL_RESULTS_SUBMITTED";
                }
                if ("idle".equals(requiredText(session, "status")) && !completed) {
                    // After a continuation event, Managed Agents may remain idle until the new turn is visible.
                    // This is also a valid transient state while session and turn reads are not atomic.
                    snapshot.reason = currentTurnId == null ? "IDLE_WAITING_FOR_NEW_TURN" : "IDLE_AWAITING_TURN_COMPLETION";
                }
                if (currentTurnId == null && previousTurnId != null) snapshot.reason = "WAITING_FOR_NEW_TURN";
                if (currentTurnId != null && previousTurnId != null && currentTurnId.equals(previousTurnId)) {
                    throw invalidResponse();
                }
                if (currentTurnId != null && previousTurnId != null && !newTurnLogged) {
                    log.info("New turn observed: sessionId={}, previousTurnId={}, newTurnId={}",
                            safeId(sessionId), safeId(previousTurnId), safeId(currentTurnId));
                    newTurnLogged = true;
                }
                readItems(sessionId, deadline, snapshot.items);
                snapshot.itemsFetched = true;
                if (completed) {
                    snapshot.reason = "TURN_COMPLETED";
                    return readAnswer(snapshot.items, currentTurnId);
                }
            } catch (AgentException exception) {
                snapshot.reason = exception.getCode();
                throw exception;
            } finally {
                logPoll(snapshot);
            }
            try {
                pause(deadline);
                session = retrieveSession(sessionId, deadline);
            } catch (AgentException exception) {
                log.info("OpenAI polling stopped: sessionId={}, rootTurnId={}, reason={}",
                        safeId(sessionId), safeId(currentTurnId), exception.getCode());
                throw exception;
            }
        }
    }

    private void readItems(String sessionId, long deadline, List<JsonNode> items) {
        var cursors = new HashSet<String>();
        String after = null;
        do {
            String cursor = after;
            JsonNode page = call(deadline, client -> client.get().uri(uri -> {
                var uriBuilder = uri.path("/agents/sessions/{id}/items").queryParam("order", "asc")
                        .queryParam("limit", 100);
                if (cursor != null) uriBuilder.queryParam("after", cursor);
                return uriBuilder.build(sessionId);
            }).retrieve().body(JsonNode.class));
            data(page).forEach(items::add);
            if (!page.path("has_more").asBoolean()) break;
            after = requiredText(page, "last_id");
            if (!cursors.add(after)) throw invalidResponse();
        } while (true);
    }

    private String readAnswer(List<JsonNode> items, String turnId) {
        var text = new ArrayList<String>();
        for (JsonNode item : items) {
            if (!"message".equals(item.path("type").asString())
                    || !"assistant".equals(item.path("role").asString())
                    || !turnId.equals(item.path("turn_id").asString())
                    || !"completed".equals(item.path("status").asString())
                    || "commentary".equals(item.path("phase").asString())) continue;
            for (JsonNode part : item.path("content")) {
                if ("output_text".equals(part.path("type").asString())) text.add(requiredText(part, "text"));
            }
        }
        String answer = String.join("\n", text);
        if (answer.isBlank()) throw upstream("EMPTY_AGENT_RESPONSE", "Agent returned no final text response.");
        return answer;
    }

    private void checkTurn(JsonNode turn, boolean toolHandled) {
        switch (requiredText(turn, "status")) {
            case "failed", "cancelled": throw upstream("AGENT_TURN_FAILED", "Agent turn failed or was cancelled.");
            // Defensive handling: these are not documented Managed Agents turn statuses.
            case "incomplete": throw upstream("AGENT_TURN_INCOMPLETE", "Agent turn stopped before completing.");
            case "requires_action": {
                if (!toolHandled) throw upstream("AGENT_REQUIRES_ACTION", "Agent turn requires external action.");
            }
            case "waiting": {
                if (!toolHandled) throw upstream("AGENT_TURN_WAITING_EXTERNAL_INPUT", "Agent turn is waiting for external input.");
            }
            case "completed", "queued", "in_progress": break;
            default: throw upstream("UNKNOWN_AGENT_TURN_STATUS", "OpenAI returned an unsupported turn status.");
        }
        if (hasError(turn)) throw upstream("AGENT_TURN_ERROR", "OpenAI reported an error on the current turn.");
    }

    private void checkSession(JsonNode session) { checkSession(session, false); }

    private void checkSession(JsonNode session, boolean allowFunctionActions) {
        try {
            switch (requiredText(session, "status")) {
                case "error": throw upstream("AGENT_SESSION_ERROR", "OpenAI reported a session error.");
                case "failed": throw upstream("AGENT_SESSION_FAILED", "Agent session failed.");
                case "cancelled": throw upstream("AGENT_SESSION_CANCELLED", "Agent session was cancelled.");
                case "incomplete": throw upstream("AGENT_SESSION_INCOMPLETE", "Agent session stopped before completing.");
                case "requires_action": {
                    if (!allowFunctionActions || !hasActions(session)) {
                        throw upstream("AGENT_REQUIRES_ACTION", "Agent requires a tool or environment that this service does not support yet.");
                    }
                }
                case "idle", "in_progress": break;
                default: throw invalidResponse();
            }
            if (hasError(session)) throw upstream("AGENT_SESSION_ERROR", "OpenAI reported a session error.");
            if (hasActions(session) && (!allowFunctionActions || !allFunctionActions(session))) {
                throw upstream("AGENT_REQUIRES_ACTION", "Agent has pending external actions.");
            }
        } catch (AgentException exception) {
            log.info("OpenAI session rejected: sessionId={}, sessionStatus={}, sessionErrorPresent={}, requiredActionsCount={}, requiredActionTypes={}, reason={}",
                    safeId(session.path("id").asString()), safeLabel(session.path("status")), hasError(session),
                    session.path("required_actions").size(), actionTypes(session), exception.getCode());
            throw exception;
        }
    }

    private record ToolCall(String turnId, String callId) {}

    private static boolean hasActions(JsonNode session) {
        return session.path("required_actions").isArray() && !session.path("required_actions").isEmpty();
    }

    private static boolean allFunctionActions(JsonNode session) {
        for (JsonNode action : session.path("required_actions")) {
            if (!"function_call".equals(action.path("type").asString())) return false;
        }
        return true;
    }

    private static boolean hasFunctionAction(JsonNode session, String turnId) {
        for (JsonNode action : session.path("required_actions")) {
            if ("function_call".equals(action.path("type").asString()) && turnId.equals(action.path("turn_id").asString())) return true;
        }
        return false;
    }

    private void handleToolCalls(String sessionId, JsonNode session, String currentTurnId, long deadline,
                                 HashSet<ToolCall> submittedCalls, List<AgentImage> images) {
        for (JsonNode action : session.path("required_actions")) {
            String turnId = requiredText(action, "turn_id");
            String callId = requiredText(action, "call_id");
            if (!currentTurnId.equals(turnId)) throw upstream("INVALID_AGENT_TOOL_CALL", "Pending tool call belongs to another turn.");
            ToolCall call = new ToolCall(turnId, callId);
            if (submittedCalls.contains(call)) continue;
            log.info("Executing agent function: functionName={}, sessionId={}, turnId={}, callId={}",
                    safeLabel(action.path("name")), safeId(sessionId), safeId(turnId), safeId(callId));
            AgentImage image = null;
            String processInstanceKey = null;
            try {
                if ("generate_fence_image".equals(action.path("name").asString())) {
                    image = imageClient.generate(action.path("arguments"), deadline);
                } else if ("start_camunda_approval".equals(action.path("name").asString())) {
                    processInstanceKey = documentServiceClient.startApproval(deadline);
                } else throw upstream("UNKNOWN_AGENT_TOOL", "Agent requested an unsupported function.");
            } catch (AgentException exception) {
                log.info("Agent tool_result: success=false, sessionId={}, turnId={}, callId={}, reason={}",
                        safeId(sessionId), safeId(turnId), safeId(callId), exception.getCode());
                submitToolResult(sessionId, Map.of("type", "agent.session.input.tool_result", "turn_id", turnId,
                        "call_id", callId, "success", false, "error", exception.getCode() + ": " + exception.getMessage()), deadline);
                throw exception;
            }
            Map<String, Object> result = image != null
                    ? Map.of("success", true, "imageId", image.id(), "imageUrl", image.url())
                    : Map.of("success", true, "processInstanceKey", processInstanceKey);
            String output = new JsonMapper().writeValueAsString(result);
            submitToolResult(sessionId, Map.of("type", "agent.session.input.tool_result", "turn_id", turnId,
                    "call_id", callId, "success", true, "output", output), deadline);
            submittedCalls.add(call);
            if (image != null) images.add(image);
        }
    }

    private void submitToolResult(String sessionId, Map<String, Object> event, long deadline) {
        try {
            execute(deadline, client -> client.post().uri("/agents/sessions/{id}/events", sessionId)
                    .body(Map.of("events", List.of(event))).retrieve().toBodilessEntity());
            log.info("Agent tool_result submitted: success={}, sessionId={}, turnId={}, callId={}",
                    event.get("success"), safeId(sessionId), safeId(String.valueOf(event.get("turn_id"))),
                    safeId(String.valueOf(event.get("call_id"))));
        } catch (AgentException exception) {
            log.info("Agent tool result submission failed: sessionId={}, reason={}", safeId(sessionId), exception.getCode());
            throw upstream("TOOL_RESULT_SUBMIT_FAILED", "Could not submit the function result to OpenAI.");
        }
    }

    private static boolean hasError(JsonNode node) {
        JsonNode error = node.path("error");
        return !error.isMissingNode() && !error.isNull() && !(error.isString() && error.asString().isBlank());
    }

    private static final class PollSnapshot {
        final int iteration;
        final String sessionId;
        JsonNode session;
        String turnId;
        JsonNode turn;
        final List<JsonNode> items = new ArrayList<>();
        boolean itemsFetched;
        String reason = "POLLING";

        PollSnapshot(int iteration, String sessionId, JsonNode session, String turnId) {
            this.iteration = iteration;
            this.sessionId = sessionId;
            this.session = session;
            this.turnId = turnId;
        }
    }

    private void logPoll(PollSnapshot snapshot) {
        JsonNode last = snapshot.items.isEmpty() ? null : snapshot.items.getLast();
        var webStatuses = new ArrayList<String>();
        for (JsonNode item : snapshot.items) {
            // Do not confuse searches from earlier conversation turns with the current search.
            if (snapshot.turnId != null && snapshot.turnId.equals(item.path("turn_id").asString())
                    && "web_search_call".equals(item.path("type").asString())) {
                webStatuses.add(safeLabel(item.path("status")));
            }
        }
        log.info("OpenAI poll: iteration={}, sessionId={}, sessionStatus={}, rootTurnId={}, turnStatus={}, sessionErrorPresent={}, turnErrorPresent={}, turnErrorCode={}, requiredActionsCount={}, requiredActionTypes={}, itemsCount={}, itemsFetched={}, lastItemType={}, lastItemStatus={}, webSearchPresent={}, webSearchStatuses={}, reason={}",
                snapshot.iteration, safeId(snapshot.sessionId), safeLabel(snapshot.session.path("status")), safeId(snapshot.turnId),
                snapshot.turn == null ? "not_observed" : safeLabel(snapshot.turn.path("status")), hasError(snapshot.session),
                snapshot.turn != null && hasError(snapshot.turn),
                snapshot.turn == null ? "not_observed" : safeLabel(snapshot.turn.path("error").path("code")), snapshot.session.path("required_actions").size(), actionTypes(snapshot.session),
                snapshot.items.size(), snapshot.itemsFetched, last == null ? "not_observed" : safeLabel(last.path("type")),
                last == null ? "not_observed" : safeLabel(last.path("status")),
                snapshot.itemsFetched ? !webStatuses.isEmpty() : "unknown", webStatuses, snapshot.reason);
    }

    private static List<String> actionTypes(JsonNode session) {
        var types = new ArrayList<String>();
        for (JsonNode action : session.path("required_actions")) types.add(safeLabel(action.path("type")));
        return types;
    }

    private String safeId(String value) {
        if (value == null) return "not_observed";
        if (value.contains(properties.getApiKey()) || !value.matches("[A-Za-z0-9_-]{1,128}")) return "redacted";
        return value;
    }

    // Only protocol labels may reach logs. Never log errors, queries, arguments, URLs or content.
    private static String safeLabel(JsonNode node) {
        String value = node.asString();
        if (value == null) return "not_observed";
        return switch (value) {
            case "error", "idle", "queued", "in_progress", "waiting", "completed", "failed", "cancelled", "incomplete", "requires_action",
                 "message", "reasoning", "web_search_call", "function_call", "function_call_output", "environment_connection",
                 "generate_fence_image", "start_camunda_approval",
                 "mcp_call", "command_execution", "agent_message", "create_subagent_call", "send_subagent_input_call",
                 "resume_subagent_call", "wait_for_subagents_call", "interrupt_subagent_call", "close_subagent_call",
                 "context_length_exceeded", "server_error", "rate_limit_exceeded", "invalid_request", "tool_error" -> value;
            default -> "unrecognized";
        };
    }

    @FunctionalInterface
    private interface Request<T> { T execute(RestClient client); }

    private JsonNode call(long deadline, Request<JsonNode> request) {
        JsonNode result = execute(deadline, request);
        if (result == null || !result.isObject()) throw invalidResponse();
        return result;
    }

    private <T> T execute(long deadline, Request<T> request) {
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(remaining(deadline));
        RestClient client = builder.clone().requestFactory(factory)
                .defaultHeaders(headers -> headers.setBearerAuth(properties.getApiKey()))
                .defaultHeader("OpenAI-Beta", "agents=v1")
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .requestInterceptor((req, body, execution) -> {
                    log.info("Calling OpenAI: method={}", req.getMethod());
                    var response = execution.execute(req, body);
                    log.info("OpenAI HTTP status={}", response.getStatusCode().value());
                    return response;
                }).build();
        try {
            T result = request.execute(client);
            remaining(deadline);
            return result;
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            log.warn("OpenAI request rejected: status={}", status);
            if (status == 429) throw new AgentException(HttpStatus.SERVICE_UNAVAILABLE, "OPENAI_RATE_LIMIT", "OpenAI rate limit exceeded.");
            if (status >= 500) throw new AgentException(HttpStatus.SERVICE_UNAVAILABLE, "OPENAI_UNAVAILABLE", "OpenAI is temporarily unavailable.");
            throw upstream("OPENAI_REQUEST_REJECTED", "OpenAI rejected the request. Check agent access and service configuration.");
        } catch (ResourceAccessException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) throw timedOut();
            }
            throw new AgentException(HttpStatus.SERVICE_UNAVAILABLE, "OPENAI_CONNECTION_ERROR", "Could not connect to OpenAI.");
        } catch (RestClientException exception) {
            throw invalidResponse();
        }
    }

    private Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw timedOut();
        return Duration.ofNanos(nanos);
    }

    private void pause(long deadline) {
        pause(deadline, pollInterval);
    }

    private void pause(long deadline, Duration interval) {
        try {
            Thread.sleep(Duration.ofNanos(Math.min(interval.toNanos(), remaining(deadline).toNanos())));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AgentException(HttpStatus.SERVICE_UNAVAILABLE, "REQUEST_INTERRUPTED", "Agent request interrupted.");
        }
    }

    private static JsonNode data(JsonNode page) {
        JsonNode data = page.path("data");
        if (!data.isArray()) throw invalidResponse();
        return data;
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString() || value.asString().isBlank()) throw invalidResponse();
        return value.asString();
    }

    private static void requireConfiguration(String value, String name) {
        if (!StringUtils.hasText(value)) throw new AgentException(HttpStatus.SERVICE_UNAVAILABLE,
                "OPENAI_NOT_CONFIGURED", "Environment variable " + name + " is required.");
    }

    private static AgentException upstream(String code, String message) {
        return new AgentException(HttpStatus.BAD_GATEWAY, code, message);
    }

    private static AgentException invalidResponse() {
        return upstream("INVALID_OPENAI_RESPONSE", "OpenAI returned an invalid response.");
    }

    private static AgentException timedOut() {
        return new AgentException(HttpStatus.GATEWAY_TIMEOUT, "OPENAI_TIMEOUT", "OpenAI response timed out.");
    }
}
