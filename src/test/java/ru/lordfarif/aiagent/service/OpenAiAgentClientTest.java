package ru.lordfarif.aiagent.service;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Base64;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.io.TempDir;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import ru.lordfarif.aiagent.config.OpenAiProperties;
import ru.lordfarif.aiagent.config.DocumentServiceProperties;
import ru.lordfarif.aiagent.dto.AgentChatResponse;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class OpenAiAgentClientTest {
    @TempDir Path imageDirectory;
    HttpServer server;
    OpenAiProperties properties;
    java.util.concurrent.ExecutorService executor;
    final ConcurrentLinkedQueue<Reply> replies = new ConcurrentLinkedQueue<>();
    final List<Request> requests = new CopyOnWriteArrayList<>();
    record Reply(int status, String body, long delay) {}
    record Request(String method, String uri, String authorization, String beta, String body) {}

    @BeforeEach void start() throws Exception {
        properties = new OpenAiProperties();
        properties.setApiKey("test-key");
        properties.setAgentId("agent_existing");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/v1/", exchange -> {
            try (exchange) {
                requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("OpenAI-Beta"),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                Reply reply = replies.poll();
                if (reply == null) reply = new Reply(500, "{}", 0);
                try { Thread.sleep(reply.delay()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(reply.status(), body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
    }

    @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }

    OpenAiAgentClient client(Duration timeout) {
        var builder = RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        var imageClient = new OpenAiImageClient(properties, new ImageStorageService(imageDirectory), builder.clone());
        var documentClient = new DocumentServiceClient(new DocumentServiceProperties(), builder.clone());
        return new OpenAiAgentClient(properties, builder, timeout, Duration.ofMillis(1), imageClient, documentClient);
    }
    OpenAiAgentClient client() { return client(Duration.ofSeconds(5)); }
    void reply(String body) { replies.add(new Reply(200, body, 0)); }
    void session(String status) { reply("{\"id\":\"sess_1\",\"status\":\"" + status + "\"}"); }
    void turn(String status) { reply("{\"data\":[{\"id\":\"turn_1\",\"status\":\"" + status + "\",\"subagent_id\":null}]}"); }
    String item(String phase, String text) {
        return "{\"type\":\"message\",\"role\":\"assistant\",\"turn_id\":\"turn_1\",\"status\":\"completed\",\"phase\":\""
                + phase + "\",\"content\":[{\"type\":\"output_text\",\"text\":\"" + text + "\"}]}";
    }

    @Test void reusesDefinitionPollsTurnAndReadsAllPages() {
        session("idle"); // Idle alone must not be treated as success.
        reply("{\"data\":[]}");
        reply("{\"data\":[],\"has_more\":false}");
        session("in_progress");
        turn("in_progress");
        reply("{\"data\":[],\"has_more\":false}");
        session("idle");
        turn("completed");
        reply("{\"data\":[" + item("commentary", "working") + "],\"has_more\":true,\"last_id\":\"msg_1\"}");
        reply("{\"data\":[" + item("final_answer", "Ответ Ланы") + "],\"has_more\":false}");
        assertThat(client().chat("Бюджет 30000", null)).isEqualTo(new AgentChatResponse("sess_1", "Ответ Ланы"));
        assertThat(requests.getFirst().method()).isEqualTo("POST");
        assertThat(requests.getFirst().uri()).isEqualTo("/v1/agents/sessions");
        assertThat(new JsonMapper().readTree(requests.getFirst().body())).isEqualTo(new JsonMapper().readTree("""
                {"agent_id":"agent_existing","environment":{"type":"none"},"input":"Бюджет 30000","stream":false}
                """));
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.authorization()).isEqualTo("Bearer test-key");
            assertThat(request.beta()).isEqualTo("agents=v1");
        });
        assertThat(requests.getLast().uri()).endsWith("items?order=asc&limit=100&after=msg_1");
        assertThat(replies).isEmpty();
    }

    void existingSession() {
        reply("{\"id\":\"sess_1\",\"status\":\"idle\",\"agent\":{\"id\":\"agent_existing\"}}");
    }

    @Test void secondRequestContinuesSameSessionAndWaitsForNewTurn() {
        session("idle"); turn("completed");
        reply("{\"data\":[" + item("final_answer", "first answer") + "],\"has_more\":false}");
        var first = client().chat("first", null);
        assertThat(first.sessionId()).isEqualTo("sess_1");
        existingSession(); turn("completed"); // Snapshot of the historical turn.
        replies.add(new Reply(202, "", 0)); // Events POST has no JSON body.
        reply("{\"data\":[]}"); // Accepted does not yet mean a new turn exists.
        reply("{\"data\":[],\"has_more\":false}");
        session("idle");
        reply("{\"data\":[]}");
        reply("{\"data\":[],\"has_more\":false}");
        session("in_progress");
        reply("{\"data\":[{\"id\":\"turn_2\",\"status\":\"in_progress\",\"subagent_id\":null}]}");
        reply("{\"data\":[],\"has_more\":false}");
        session("idle");
        reply("{\"data\":[{\"id\":\"turn_2\",\"status\":\"completed\",\"subagent_id\":null}]}");
        reply("{\"data\":[" + item("final_answer", "old answer") + ","
                + item("final_answer", "new answer").replace("turn_1", "turn_2") + "],\"has_more\":false}");
        // A separate client instance demonstrates that no in-memory conversation state is needed.
        assertThat(client().chat("continue", first.sessionId()))
                .isEqualTo(new AgentChatResponse("sess_1", "new answer"));
        var posts = requests.stream().filter(r -> r.method().equals("POST")).toList();
        assertThat(posts).hasSize(2);
        assertThat(posts.get(0).uri()).isEqualTo("/v1/agents/sessions");
        assertThat(posts.get(1).uri()).isEqualTo("/v1/agents/sessions/sess_1/events");
        var mapper = new JsonMapper();
        assertThat(mapper.readTree(posts.get(1).body())).isEqualTo(mapper.readTree("""
                {"events":[{"type":"agent.session.input.message","input":[
                    {"role":"user","content":[{"type":"input_text","text":"continue"}]}]}]}
                """));
        assertThat(requests.stream().filter(r -> r.uri().contains("/turns?order=asc") && r.uri().contains("after=turn_1")))
                .hasSize(4);
        assertThat(replies).isEmpty();
    }

    @Test void missingSessionDoesNotCreateAnother() {
        replies.add(new Reply(404, "{\"error\":\"private details\"}", 0));
        assertThatThrownBy(() -> client().chat("hello", "missing")).isInstanceOfSatisfying(AgentException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(e.getCode()).isEqualTo("SESSION_NOT_FOUND");
            assertThat(e.getMessage()).doesNotContain("private");
        });
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().method()).isEqualTo("GET");
    }

    @ParameterizedTest @ValueSource(strings = {"", " "})
    void rejectsBlankSessionId(String sessionId) {
        assertThatThrownBy(() -> client().chat("hello", sessionId)).isInstanceOfSatisfying(AgentException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(requests).isEmpty();
    }

    @Test void rejectsSessionForAnotherAgent() {
        reply("{\"id\":\"sess_1\",\"status\":\"idle\",\"agent\":{\"id\":\"another_agent\"}}");
        assertThatThrownBy(() -> client().chat("hello", "sess_1")).isInstanceOfSatisfying(AgentException.class,
                e -> assertThat(e.getCode()).isEqualTo("SESSION_AGENT_MISMATCH"));
        assertThat(requests).hasSize(1);
    }

    @Test void rejectsBusySessionWithoutSteeringItsTurn() {
        reply("{\"id\":\"sess_1\",\"status\":\"in_progress\",\"agent\":{\"id\":\"agent_existing\"}}");
        assertThatThrownBy(() -> client().chat("hello", "sess_1")).isInstanceOfSatisfying(AgentException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest @ValueSource(ints = {400, 401, 404, 429, 500, 503})
    void mapsContinuationPostErrorsWithoutCreatingSession(int status) {
        existingSession(); turn("completed");
        replies.add(new Reply(status, "{\"error\":\"private test-key\"}", 0));
        assertThatThrownBy(() -> client().chat("hello", "sess_1")).isInstanceOfSatisfying(AgentException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status == 429 || status >= 500 ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY);
            assertThat(e.getMessage()).doesNotContain("private", "test-key");
        });
        assertThat(requests).hasSize(3);
        assertThat(requests.getLast().uri()).endsWith("/sess_1/events");
    }

    @Test void timesOutWaitingForNewTurnInsteadOfReturningOldAnswer() {
        existingSession(); turn("completed"); replies.add(new Reply(202, "", 0));
        reply("{\"data\":[]}");
        reply("{\"data\":[],\"has_more\":false}");
        replies.add(new Reply(200, "{\"id\":\"sess_1\",\"status\":\"idle\"}", 1000));
        assertThatThrownBy(() -> client(Duration.ofMillis(500)).chat("hello", "sess_1"))
                .isInstanceOfSatisfying(AgentException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT));
    }

    @ParameterizedTest @ValueSource(ints = {400, 401, 403, 404, 429, 500, 503})
    void sanitizesOpenAiHttpErrors(int status) {
        replies.add(new Reply(status, "{\"error\":\"test-key private upstream details\"}", 0));
        assertThatThrownBy(() -> client().chat("hello", null)).isInstanceOfSatisfying(AgentException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status == 429 || status >= 500 ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY);
            assertThat(e.getMessage()).doesNotContain("test-key", "private upstream");
            assertThat(e.getCause()).isNull();
        });
    }

    @Test void rejectsMissingKeyBeforeHttp() {
        properties.setApiKey(" ");
        assertThatThrownBy(() -> client().chat("hello", null)).isInstanceOfSatisfying(AgentException.class,
                e -> assertThat(e.getMessage()).contains("OPENAI_API_KEY"));
        assertThat(requests).isEmpty();
    }
    @Test void rejectsMissingAgentBeforeHttp() {
        properties.setAgentId(null);
        assertThatThrownBy(() -> client().chat("hello", null)).isInstanceOfSatisfying(AgentException.class,
                e -> assertThat(e.getMessage()).contains("OPENAI_AGENT_ID"));
        assertThat(requests).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings = {"failed", "cancelled"})
    void rejectsFailedTurns(String status) {
        session("idle"); turn(status);
        assertThatThrownBy(() -> client().chat("hello", null)).isInstanceOfSatisfying(AgentException.class,
                e -> assertThat(e.getCode()).isEqualTo("AGENT_TURN_FAILED"));
    }
    @ParameterizedTest @ValueSource(strings = {"failed", "requires_action"})
    void rejectsUnsupportedSessionState(String status) {
        session(status);
        assertThatThrownBy(() -> client().chat("hello", null)).isInstanceOf(AgentException.class);
        assertThat(requests).hasSize(1);
    }
    @Test void rejectsEmptyAnswer() {
        session("idle"); turn("completed"); reply("{\"data\":[],\"has_more\":false}");
        assertThatThrownBy(() -> client().chat("hello", null)).isInstanceOfSatisfying(AgentException.class,
                e -> assertThat(e.getCode()).isEqualTo("EMPTY_AGENT_RESPONSE"));
    }
    String webSearch(String status) {
        return "{\"id\":\"ws_1\",\"type\":\"web_search_call\",\"turn_id\":\"turn_1\",\"status\":\"" + status
                + "\",\"action\":{\"type\":\"search\",\"query\":\"sensitive-query\"}}";
    }

    @Test void logsWebSearchProgressAndWaitsForTurnCompletion(CapturedOutput output) {
        session("in_progress"); turn("in_progress");
        reply("{\"data\":[" + webSearch("in_progress") + "],\"has_more\":false}");
        session("in_progress"); turn("in_progress");
        reply("{\"data\":[" + webSearch("completed") + "],\"has_more\":false}");
        session("idle"); turn("completed");
        reply("{\"data\":[" + webSearch("completed") + "," + item("final_answer", "sensitive-answer") + "],\"has_more\":false}");
        assertThat(client().chat("sensitive-prompt", null).response()).isEqualTo("sensitive-answer");
        assertThat(output.getOut()).contains("sessionId=sess_1", "rootTurnId=turn_1", "webSearchPresent=true",
                "webSearchStatuses=[in_progress]", "webSearchStatuses=[completed]", "itemsCount=2",
                "lastItemType=web_search_call", "lastItemStatus=in_progress", "reason=TURN_COMPLETED");
        assertThat(output.getAll()).doesNotContain("sensitive-query", "sensitive-answer", "sensitive-prompt", "test-key", "Authorization");
        assertThat(replies).isEmpty();
    }

    @Test void reportsRequiredActionsWithoutLoggingArguments(CapturedOutput output) {
        reply("""
                {"id":"sess_1","status":"requires_action","required_actions":[
                    {"type":"function_call","arguments":{"secret":"sensitive-arguments"}},
                    {"type":"environment_connection","environment_id":"secret-env"}]}
                """);
        assertThatThrownBy(() -> client().chat("hello", null)).isInstanceOfSatisfying(AgentException.class,
                e -> assertThat(e.getCode()).isEqualTo("AGENT_REQUIRES_ACTION"));
        assertThat(output.getOut()).contains("requiredActionsCount=2", "requiredActionTypes=[function_call, environment_connection]",
                "reason=AGENT_REQUIRES_ACTION", "itemsFetched=false");
        assertThat(output.getAll()).doesNotContain("sensitive-arguments", "secret-env");
        assertThat(requests).hasSize(1); // No diagnostic HTTP call can hide an already known failure.
    }

    @Test void stopsOnSessionErrorEvenWithNonterminalStatus(CapturedOutput output) {
        reply("{\"id\":\"sess_1\",\"status\":\"in_progress\",\"error\":\"sensitive-error test-key\"}");
        assertThatThrownBy(() -> client().chat("hello", null)).isInstanceOfSatisfying(AgentException.class,
                e -> assertThat(e.getCode()).isEqualTo("AGENT_SESSION_ERROR"));
        assertThat(output.getOut()).contains("sessionErrorPresent=true", "reason=AGENT_SESSION_ERROR");
        assertThat(output.getAll()).doesNotContain("sensitive-error", "test-key");
        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest @ValueSource(strings = {"failed", "cancelled", "incomplete", "requires_action", "waiting"})
    void stopsOnTerminalOrBlockedTurnBeforeDiagnosticNetworkCalls(String status, CapturedOutput output) {
        session("in_progress"); turn(status);
        boolean needsSessionRefresh = status.equals("waiting") || status.equals("requires_action");
        if (needsSessionRefresh) session("in_progress");
        assertThatThrownBy(() -> client().chat("hello", null)).isInstanceOfSatisfying(AgentException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
        assertThat(output.getOut()).contains("rootTurnId=turn_1", "turnStatus=" + status).doesNotContain("reason=OPENAI_TIMEOUT");
        assertThat(requests).hasSize(needsSessionRefresh ? 3 : 2);
    }

    @ParameterizedTest @ValueSource(strings = {"cancelled", "incomplete"})
    void rejectsUnexpectedTerminalSessionStatuses(String status) {
        session(status);
        assertThatThrownBy(() -> client().chat("hello", null)).isInstanceOfSatisfying(AgentException.class,
                e -> assertThat(e.getCode()).startsWith("AGENT_SESSION_"));
        assertThat(requests).hasSize(1);
    }

    @Test void idleWithoutCompletedTurnIsTransientUntilDeadline(CapturedOutput output) {
        session("idle"); turn("in_progress"); reply("{\"data\":[],\"has_more\":false}");
        session("idle"); turn("in_progress");
        assertThatThrownBy(() -> client(Duration.ofMillis(100)).chat("hello", null)).isInstanceOfSatisfying(AgentException.class,
                e -> assertThat(e.getCode()).isNotEqualTo("AGENT_IDLE_WITHOUT_COMPLETED_TURN"));
        assertThat(output.getOut()).doesNotContain("AGENT_IDLE_WITHOUT_COMPLETED_TURN");
        assertThat(replies).isEmpty();
    }

    @Test void logsSafeTurnErrorCodeWithoutErrorMessage(CapturedOutput output) {
        session("in_progress");
        reply("""
                {"data":[{"id":"turn_1","status":"failed","subagent_id":null,
                    "error":{"code":"context_length_exceeded","message":"secret upstream prompt test-key"}}]}
                """);
        assertThatThrownBy(() -> client().chat("hello", null)).isInstanceOfSatisfying(AgentException.class,
                e -> assertThat(e.getCode()).isEqualTo("AGENT_TURN_FAILED"));
        assertThat(output.getOut()).contains("turnErrorPresent=true", "turnErrorCode=context_length_exceeded");
        assertThat(output.getAll()).doesNotContain("secret upstream", "test-key");
    }

    @Test void idleWithoutAnyCurrentTurnIsTransient(CapturedOutput output) {
        session("idle"); reply("{\"data\":[]}"); reply("{\"data\":[],\"has_more\":false}");
        session("idle"); reply("{\"data\":[]}");
        assertThatThrownBy(() -> client(Duration.ofMillis(100)).chat("hello", null)).isInstanceOfSatisfying(AgentException.class,
                e -> assertThat(e.getCode()).isNotEqualTo("AGENT_IDLE_WITHOUT_COMPLETED_TURN"));
        assertThat(output.getOut()).doesNotContain("AGENT_IDLE_WITHOUT_COMPLETED_TURN");
    }

    @Test void incompleteSearchDoesNotReplaceTurnOutcome(CapturedOutput output) {
        session("in_progress"); turn("in_progress");
        reply("{\"data\":[" + webSearch("incomplete") + "],\"has_more\":false}");
        session("idle"); turn("completed");
        reply("{\"data\":[" + webSearch("incomplete") + "," + item("final_answer", "Search did not finish") + "],\"has_more\":false}");
        assertThat(client().chat("hello", null).response()).isEqualTo("Search did not finish");
        assertThat(output.getOut()).contains("webSearchStatuses=[incomplete]", "reason=TURN_COMPLETED");
    }

    @Test void ignoresWebSearchFromPreviousTurns(CapturedOutput output) {
        session("idle"); turn("completed");
        reply("{\"data\":[" + webSearch("completed").replace("turn_1", "old_turn") + ","
                + item("final_answer", "answer") + "],\"has_more\":false}");
        assertThat(client().chat("hello", null).response()).isEqualTo("answer");
        assertThat(output.getOut()).contains("webSearchPresent=false", "webSearchStatuses=[]");
    }

    @Test void timeoutWhileWebSearchStillRunsIsNotTerminalFailure(CapturedOutput output) {
        session("in_progress"); turn("in_progress");
        reply("{\"data\":[" + webSearch("in_progress") + "],\"has_more\":false}");
        replies.add(new Reply(200, "{\"id\":\"sess_1\",\"status\":\"in_progress\"}", 1000));
        assertThatThrownBy(() -> client(Duration.ofMillis(500)).chat("hello", null)).isInstanceOfSatisfying(AgentException.class,
                e -> assertThat(e.getCode()).isEqualTo("OPENAI_TIMEOUT"));
        assertThat(output.getOut()).contains("turnStatus=in_progress", "webSearchStatuses=[in_progress]");
    }

    byte[] png() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }

    void fenceAction(String name, String arguments) {
        reply("{\"id\":\"sess_1\",\"status\":\"requires_action\",\"required_actions\":[{"
                + "\"type\":\"function_call\",\"turn_id\":\"turn_1\",\"call_id\":\"call_1\",\"name\":\"" + name
                + "\",\"arguments\":" + arguments + "}]}");
    }

    void fenceAction() { fenceAction("generate_fence_image", "{\"fenceType\":\"metal fence\",\"color\":\"green\",\"description\":\"private-description\"}"); }
    void camundaAction() { fenceAction("start_camunda_approval", "{}"); }
    void imageReply(byte[] png) { reply("{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(png) + "\"}]}"); }
    void acceptedToolResult() { replies.add(new Reply(202, "", 0)); }
    void emptyItems() { reply("{\"data\":[],\"has_more\":false}"); }
    tools.jackson.databind.JsonNode toolResult() {
        var request = requests.stream().filter(r -> r.uri().endsWith("/events")).findFirst().orElseThrow();
        return new JsonMapper().readTree(request.body()).path("events").get(0);
    }

    @Test void generatesStoresSubmitsAndResumesSameTurnWithoutRepeatingCall(CapturedOutput output) throws Exception {
        byte[] bytes = png();
        fenceAction(); turn("waiting"); imageReply(bytes); acceptedToolResult(); emptyItems();
        fenceAction(); turn("waiting"); emptyItems(); // Stale required action after acceptance.
        session("in_progress"); turn("in_progress"); emptyItems();
        session("idle"); turn("completed");
        reply("{\"data\":[" + item("final_answer", "Изображение готово. Можно ли отправить этот вариант на согласование Тамаре Геннадьевне?") + "],\"has_more\":false}");
        var result = client().chat("draw a fence", null);
        assertThat(result.sessionId()).isEqualTo("sess_1");
        assertThat(result.response()).contains("Тамаре Геннадьевне");
        assertThat(result.images()).hasSize(1);
        var image = result.images().getFirst();
        assertThat(image.url()).isEqualTo("/api/v1/images/" + image.id());
        assertThat(Files.readAllBytes(imageDirectory.resolve(image.id() + ".png"))).isEqualTo(bytes);
        var imageRequests = requests.stream().filter(r -> r.uri().equals("/v1/images/generations")).toList();
        assertThat(imageRequests).hasSize(1);
        var body = new JsonMapper().readTree(imageRequests.getFirst().body());
        assertThat(body.path("model").asString()).isEqualTo("gpt-image-2.5-flare");
        assertThat(body.path("n").asInt()).isEqualTo(1);
        assertThat(body.path("size").asString()).isEqualTo("1536x1024");
        assertThat(body.path("quality").asString()).isEqualTo("medium");
        assertThat(body.path("output_format").asString()).isEqualTo("png");
        assertThat(body.path("prompt").asString()).contains("metal fence", "green", "private-description", "No people.", "No watermarks.");
        assertThat(imageRequests.getFirst().authorization()).isEqualTo("Bearer test-key");
        var event = toolResult();
        assertThat(event.path("type").asString()).isEqualTo("agent.session.input.tool_result");
        assertThat(event.path("turn_id").asString()).isEqualTo("turn_1");
        assertThat(event.path("call_id").asString()).isEqualTo("call_1");
        assertThat(event.path("success").asBoolean()).isTrue();
        var resultBody = new JsonMapper().readTree(event.path("output").asString());
        assertThat(resultBody.path("imageId").asString()).isEqualTo(image.id());
        assertThat(resultBody.path("imageUrl").asString()).isEqualTo(image.url());
        assertThat(resultBody.path("success").asBoolean()).isTrue();
        assertThat(requests.stream().filter(r -> r.uri().endsWith("/events"))).hasSize(1);
        assertThat(event.toString()).doesNotContain("b64", Base64.getEncoder().encodeToString(bytes));
        assertThat(output.getAll()).doesNotContain("private-description", "test-key", Base64.getEncoder().encodeToString(bytes));
        assertThat(replies).isEmpty();
    }

    @Test void continuesConversationAndRefreshesSessionWhenTurnStartsWaiting() throws Exception {
        session("idle"); turn("completed");
        reply("{\"data\":[" + item("final_answer", "Какой цвет?") + "],\"has_more\":false}");
        var first = client().chat("Хочу металлический забор", null);
        assertThat(first.images()).isEmpty();
        existingSession(); turn("completed"); acceptedToolResult(); // Continuation message POST.
        reply("{\"data\":[{\"id\":\"turn_2\",\"status\":\"waiting\",\"subagent_id\":null}]}");
        // The previous session snapshot was idle; the fresh snapshot exposes the function call.
        reply("""
                {"id":"sess_1","status":"requires_action","required_actions":[{"type":"function_call",
                "turn_id":"turn_2","call_id":"call_2","name":"generate_fence_image",
                "arguments":{"fenceType":"metal fence","color":"green"}}]}
                """);
        imageReply(png()); acceptedToolResult(); emptyItems();
        session("idle");
        reply("{\"data\":[{\"id\":\"turn_2\",\"status\":\"completed\",\"subagent_id\":null}]}");
        reply("{\"data\":[" + item("final_answer", "Готово").replace("turn_1", "turn_2") + "],\"has_more\":false}");
        var second = client().chat("Зелёный", first.sessionId());
        assertThat(second.images()).hasSize(1);
        assertThat(second.sessionId()).isEqualTo(first.sessionId());
        assertThat(requests.stream().filter(r -> r.method().equals("POST") && r.uri().equals("/v1/agents/sessions"))).hasSize(1);
        var resultRequest = requests.stream().filter(r -> r.uri().endsWith("/events")).toList().getLast();
        var event = new JsonMapper().readTree(resultRequest.body()).path("events").get(0);
        assertThat(event.path("turn_id").asString()).isEqualTo("turn_2");
        assertThat(event.path("call_id").asString()).isEqualTo("call_2");
        assertThat(replies).isEmpty();
    }

    @Test void startsCamundaApprovalWithFixedVariablesAndContinuesSameTurn() throws Exception {
        session("idle"); turn("completed");
        reply("{\"data\":[" + item("final_answer", "Изображение готово") + "],\"has_more\":false}");
        var first = client().chat("first", null);
        existingSession(); turn("completed"); replies.add(new Reply(202, "", 0));
        reply("{\"data\":[{\"id\":\"turn_2\",\"status\":\"waiting\",\"subagent_id\":null}]}");
        reply("{\"id\":\"sess_1\",\"status\":\"requires_action\",\"required_actions\":[{\"type\":\"function_call\",\"turn_id\":\"turn_2\",\"call_id\":\"call_camunda\",\"name\":\"start_camunda_approval\",\"arguments\":{}}]}");
        reply("{\"processInstanceKey\":\"987654321\",\"processDefinitionId\":\"document-approval-process\",\"status\":\"ACTIVE\"}");
        acceptedToolResult();
        reply("{\"data\":[{\"id\":\"turn_2\",\"status\":\"waiting\",\"subagent_id\":null}]}");
        session("in_progress"); reply("{\"data\":[{\"id\":\"turn_2\",\"status\":\"in_progress\",\"subagent_id\":null}]}");
        reply("{\"data\":[],\"has_more\":false}"); session("idle");
        reply("{\"data\":[{\"id\":\"turn_2\",\"status\":\"completed\",\"subagent_id\":null}]}");
        reply("{\"data\":[{\"type\":\"message\",\"role\":\"assistant\",\"turn_id\":\"turn_2\",\"status\":\"completed\",\"phase\":\"final_answer\",\"content\":[{\"type\":\"output_text\",\"text\":\"Процесс согласования запущен: 987654321\"}]}],\"has_more\":false}");
        var result = client().chat("Да, отправляй на согласование Тамаре Геннадьевне", first.sessionId());
        assertThat(result.response()).contains("987654321");
        var documentRequests = requests.stream().filter(r -> r.uri().endsWith("/api/v1/processes/document-approval")).toList();
        assertThat(documentRequests).hasSize(1);
        assertThat(new JsonMapper().readTree(documentRequests.getFirst().body())).isEqualTo(new JsonMapper().readTree("""
                {"applicationId":"APP-FENCE-001","applicantName":"Николай Николаевич","documentType":"FENCE_APPROVAL"}
                """));
        var event = requests.stream().filter(r -> r.uri().endsWith("/events")).toList().getLast();
        var tool = new JsonMapper().readTree(event.body()).path("events").get(0);
        assertThat(tool.path("success").asBoolean()).isTrue();
        assertThat(tool.path("turn_id").asString()).isEqualTo("turn_2");
        assertThat(tool.path("call_id").asString()).isEqualTo("call_camunda");
        assertThat(new JsonMapper().readTree(tool.path("output").asString()).path("processInstanceKey").asString()).isEqualTo("987654321");
    }

    @ParameterizedTest @ValueSource(ints = {400, 500})
    void documentServiceErrorsSubmitFailure(int status) {
        existingSession(); turn("completed"); replies.add(new Reply(202, "", 0));
        reply("{\"data\":[{\"id\":\"turn_2\",\"status\":\"waiting\",\"subagent_id\":null}]}");
        reply("{\"id\":\"sess_1\",\"status\":\"requires_action\",\"required_actions\":[{\"type\":\"function_call\",\"turn_id\":\"turn_2\",\"call_id\":\"call_camunda\",\"name\":\"start_camunda_approval\",\"arguments\":{}}]}");
        replies.add(new Reply(status, "{\"error\":\"unavailable\"}", 0));
        acceptedToolResult();
        assertThatThrownBy(() -> client().chat("Да", "sess_1")).isInstanceOfSatisfying(AgentException.class,
                e -> assertThat(e.getCode()).isIn("CAMUNDA_START_FAILED", "DOCUMENT_SERVICE_UNAVAILABLE"));
        assertThat(toolResult().path("success").asBoolean()).isFalse();
    }

    @ParameterizedTest @ValueSource(ints = {400, 401, 429, 500, 503})
    void imageApiErrorsSubmitFailureWithoutSuccess(int status) throws Exception {
        fenceAction(); turn("waiting");
        replies.add(new Reply(status, "{\"error\":\"private test-key\"}", 0)); acceptedToolResult();
        assertThatThrownBy(() -> client().chat("hello", null)).isInstanceOfSatisfying(AgentException.class,
                e -> assertThat(e.getCode()).isEqualTo("IMAGE_GENERATION_FAILED"));
        var event = toolResult();
        assertThat(event.path("success").asBoolean()).isFalse();
        assertThat(event.path("error").asString()).contains("IMAGE_GENERATION_FAILED").doesNotContain("test-key", "private");
        assertThat(event.has("output")).isFalse();
        try (var files = Files.list(imageDirectory)) { assertThat(files).isEmpty(); }
    }

    @ParameterizedTest @ValueSource(strings = {"not base64!!!", "", "aGVsbG8="})
    void rejectsInvalidImageBytesAndSubmitsFailure(String base64) throws Exception {
        fenceAction(); turn("waiting"); reply("{\"data\":[{\"b64_json\":\"" + base64 + "\"}]}"); acceptedToolResult();
        assertThatThrownBy(() -> client().chat("hello", null)).isInstanceOfSatisfying(AgentException.class,
                e -> assertThat(e.getCode()).isEqualTo("IMAGE_RESPONSE_INVALID"));
        assertThat(toolResult().path("success").asBoolean()).isFalse();
        try (var files = Files.list(imageDirectory)) { assertThat(files).isEmpty(); }
    }

    @Test void unknownFunctionSubmitsFailureAndDoesNotCallImageApi() {
        fenceAction("unknown_function", "{}"); turn("waiting"); acceptedToolResult();
        assertThatThrownBy(() -> client().chat("hello", null)).isInstanceOfSatisfying(AgentException.class,
                e -> assertThat(e.getCode()).isEqualTo("UNKNOWN_AGENT_TOOL"));
        assertThat(toolResult().path("success").asBoolean()).isFalse();
        assertThat(requests).noneMatch(r -> r.uri().contains("/images/"));
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "{\"fenceType\":5,\"color\":\"red\"}", "{\"fenceType\":\"metal\",\"color\":\"\"}"})
    void rejectsInvalidToolArguments(String arguments) {
        fenceAction("generate_fence_image", arguments); turn("waiting"); acceptedToolResult();
        assertThatThrownBy(() -> client().chat("hello", null)).isInstanceOfSatisfying(AgentException.class,
                e -> assertThat(e.getCode()).isEqualTo("INVALID_AGENT_TOOL_ARGUMENTS"));
        assertThat(requests).noneMatch(r -> r.uri().contains("/images/"));
    }

    @Test void reportsToolResultSubmissionFailureWithoutRegenerating() throws Exception {
        fenceAction(); turn("waiting"); imageReply(png()); replies.add(new Reply(500, "{}", 0));
        assertThatThrownBy(() -> client().chat("hello", null)).isInstanceOfSatisfying(AgentException.class,
                e -> assertThat(e.getCode()).isEqualTo("TOOL_RESULT_SUBMIT_FAILED"));
        assertThat(requests.stream().filter(r -> r.uri().endsWith("/images/generations"))).hasSize(1);
        assertThat(requests.stream().filter(r -> r.uri().endsWith("/events"))).hasSize(1);
    }

    @Test void storageFailureIsReportedToAgent() throws Exception {
        Path unavailableDirectory = imageDirectory.resolve("regular-file");
        Files.writeString(unavailableDirectory, "not a directory");
        var builder = RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        var imageClient = new OpenAiImageClient(properties, new ImageStorageService(unavailableDirectory), builder.clone());
        var agent = new OpenAiAgentClient(properties, builder, Duration.ofSeconds(5), Duration.ofMillis(1), imageClient);
        fenceAction(); turn("waiting"); imageReply(png()); acceptedToolResult();
        assertThatThrownBy(() -> agent.chat("hello", null)).isInstanceOfSatisfying(AgentException.class,
                e -> assertThat(e.getCode()).isEqualTo("IMAGE_STORAGE_FAILED"));
        assertThat(toolResult().path("error").asString()).contains("IMAGE_STORAGE_FAILED");
    }

    @Test void mapsHttpTimeout() {
        replies.add(new Reply(200, "{}", 600));
        assertThatThrownBy(() -> client(Duration.ofMillis(200)).chat("hello", null))
                .isInstanceOfSatisfying(AgentException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT));
    }
    @Test void boundsEntirePollingFlow() {
        session("idle"); reply("{\"data\":[]}");
        reply("{\"data\":[],\"has_more\":false}");
        replies.add(new Reply(200, "{\"id\":\"sess_1\",\"status\":\"idle\"}", 600));
        assertThatThrownBy(() -> client(Duration.ofMillis(250)).chat("hello", null))
                .isInstanceOfSatisfying(AgentException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT));
    }
    @ParameterizedTest @ValueSource(strings = {"{", "{}", "null", "[]"})
    void rejectsMalformedResponse(String body) {
        reply(body);
        assertThatThrownBy(() -> client().chat("hello", null)).isInstanceOfSatisfying(AgentException.class,
                e -> assertThat(e.getCode()).isEqualTo("INVALID_OPENAI_RESPONSE"));
    }
}
