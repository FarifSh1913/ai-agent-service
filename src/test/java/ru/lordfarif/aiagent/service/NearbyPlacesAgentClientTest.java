package ru.lordfarif.aiagent.service;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.nio.file.Path;
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
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class NearbyPlacesAgentClientTest {
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

    final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

    ru.lordfarif.aiagent.dto.NearbyPlacesResponse run() {
        properties.getPolling().setIntervalMs(1);
        return client().runAgent(properties.getAgents().getNearbyPlaces().getId(),
                java.util.Map.of("city", "Москва", "date", java.time.LocalDate.of(2026, 9, 29)),
                ru.lordfarif.aiagent.dto.NearbyPlacesResponse.class);
    }

    String message(String phase, String result) {
        return mapper.writeValueAsString(java.util.Map.of("type", "message", "role", "assistant",
                "phase", phase, "status", "completed", "content", List.of(java.util.Map.of("text", result))));
    }

    void items(String result) {
        reply("{\"data\":[" + message("final_answer", result) + "],\"has_more\":false}");
    }

    @Test void returnsLastFinalAcrossPagesAndSerializesInput(CapturedOutput output) {
        session("in_progress");
        session("idle");
        reply("{\"data\":[" + message("final_answer", "{\"places\":[]}")
                + "],\"has_more\":true,\"last_id\":\"msg_1\"}");
        reply("{\"data\":[" + message("final_answer", """
                {"places":[
                  {"title":"Кафе «Рядом»", "type":"cafe", "address":"Москва", "lat":55.7, "lon":37.6,
                   "distanceMeters":20, "estimatedPrice":2500.50, "openAtRequestedTime":true,
                   "reason":"Ужин", "source":"Официальный сайт", "sourceUrl":"https://example.com"},
                  {"title":"Ресторан", "type":"restaurant"}]}
                """) + "," + message("commentary", "ignore") + "],\"has_more\":false}");
        var result = run();
        assertThat(result.places()).hasSize(2);
        assertThat(result.places().getFirst().title()).isEqualTo("Кафе «Рядом»");
        assertThat(result.places().getFirst().estimatedPrice()).isEqualByComparingTo("2500.50");
        assertThat(result.places().getFirst().openAtRequestedTime()).isTrue();
        var body = mapper.readTree(requests.getFirst().body());
        assertThat(body.path("agent_id").asString()).isEqualTo(properties.getAgents().getNearbyPlaces().getId());
        assertThat(body.path("environment").path("type").asString()).isEqualTo("none");
        assertThat(body.path("stream").asBoolean()).isFalse();
        assertThat(mapper.readTree(body.path("input").asString()).path("date").asString()).isEqualTo("2026-09-29");
        assertThat(requests.getLast().uri()).contains("order=asc", "limit=100", "after=msg_1");
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.authorization()).isEqualTo("Bearer test-key");
            assertThat(request.beta()).isEqualTo("agents=v1");
        });
        assertThat(output).contains("sessionId=sess_1", "status=in_progress", "status=idle")
                .doesNotContain("test-key", "Authorization", "Кафе «Рядом»");
    }

    @Test void acceptsEmptyPlaces() {
        session("idle"); items("{\"places\":[]}");
        assertThat(run().places()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"failed", "cancelled", "error"})
    void rejectsFailedSessions(String status) {
        session("in_progress"); session(status);
        assertThatThrownBy(this::run).isInstanceOfSatisfying(AgentException.class,
                error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
        assertThat(requests).hasSize(2);
    }

    @Test void failsWithoutFinalAnswer() {
        session("idle");
        reply("{\"data\":[" + message("commentary", "working") + "],\"has_more\":false}");
        assertThatThrownBy(this::run).isInstanceOfSatisfying(AgentException.class,
                error -> assertThat(error.getCode()).isEqualTo("MISSING_FINAL_ANSWER"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "null",
            "{\"places\":[] } trailing", "{\"places\":{}}"})
    void rejectsInvalidJsonOrShape(String result) {
        session("idle"); items(result);
        assertThatThrownBy(this::run).isInstanceOfSatisfying(AgentException.class,
                error -> assertThat(error.getCode()).isEqualTo("INVALID_AGENT_JSON"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"places\":null}", "{\"places\":[null]}"})
    void acceptsNullableListsForBusinessPostProcessing(String result) {
        session("idle"); items(result);
        assertThat(run()).isNotNull();
    }

    @Test void passesSessionAndParsedResultToPostProcessor() {
        session("idle"); items("{\"places\":[]}");
        var result = client().runAgent(properties.getAgents().getNearbyPlaces().getId(), java.util.Map.of(),
                ru.lordfarif.aiagent.dto.NearbyPlacesResponse.class, (sessionId, response) -> {
                    assertThat(sessionId).isEqualTo("sess_1");
                    assertThat(response.places()).isEmpty();
                    return response;
                });
        assertThat(result.places()).isEmpty();
    }

    @Test void pollingHasOverallDeadline() {
        properties.getPolling().setTimeoutMs(150);
        for (int i = 0; i < 200; i++) session("in_progress");
        assertThatThrownBy(this::run).isInstanceOfSatisfying(AgentException.class,
                error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT));
    }

    @Test void httpCallUsesOverallDeadline() {
        properties.getPolling().setTimeoutMs(150);
        replies.add(new Reply(200, "{\"id\":\"sess_1\",\"status\":\"idle\"}", 500));
        assertThatThrownBy(this::run).isInstanceOfSatisfying(AgentException.class,
                error -> assertThat(error.getCode()).isEqualTo("OPENAI_TIMEOUT"));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 429, 500})
    void mapsUpstreamHttpErrors(int status) {
        replies.add(new Reply(status, "{\"error\":\"private upstream details\"}", 0));
        assertThatThrownBy(this::run).isInstanceOfSatisfying(AgentException.class, error -> {
            assertThat(error.getStatus()).isEqualTo(status == 401 ? HttpStatus.BAD_GATEWAY : HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(error.getMessage()).doesNotContain("private upstream details");
        });
    }
}
