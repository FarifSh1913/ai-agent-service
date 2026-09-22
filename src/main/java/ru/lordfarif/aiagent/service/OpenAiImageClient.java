package ru.lordfarif.aiagent.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import javax.imageio.ImageIO;
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
import tools.jackson.databind.json.JsonMapper;
import ru.lordfarif.aiagent.config.OpenAiProperties;
import ru.lordfarif.aiagent.dto.AgentImage;

@Component
public class OpenAiImageClient {
    private static final Logger log = LoggerFactory.getLogger(OpenAiImageClient.class);
    private static final int MAX_PNG_BYTES = 30 * 1024 * 1024;
    private static final byte[] PNG_SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private final OpenAiProperties properties;
    private final ImageStorageService storage;
    private final RestClient.Builder builder;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Autowired
    public OpenAiImageClient(OpenAiProperties properties, ImageStorageService storage) {
        this(properties, storage, RestClient.builder().baseUrl("https://api.openai.com/v1"));
    }

    OpenAiImageClient(OpenAiProperties properties, ImageStorageService storage, RestClient.Builder builder) {
        this.properties = properties;
        this.storage = storage;
        this.builder = builder;
    }

    public AgentImage generate(JsonNode arguments, long deadline) {
        arguments = normalizeArguments(arguments);
        String fenceType = argument(arguments, "fenceType", true, 500);
        String color = argument(arguments, "color", true, 200);
        String description = argument(arguments, "description", false, 4000);
        String prompt = """
                Create a realistic visualization of a %s.
                Main color: %s.
                %s

                Show the fence clearly from a natural exterior perspective.
                No people.
                No text.
                No logos.
                No watermarks.
                Clean realistic residential environment.
                The fence must be the main subject.
                """.formatted(fenceType, color, description);
        log.info("generate_fence_image: function=generate_fence_image, fenceType={}, color={}, descriptionPresent={}",
                safeValue(fenceType), safeValue(color), !description.isBlank());
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(remaining(deadline));
        RestClient client = builder.clone().requestFactory(factory)
                .defaultHeaders(headers -> headers.setBearerAuth(properties.getApiKey()))
                .requestInterceptor((request, body, execution) -> {
                    log.info("Calling OpenAI Image API: model=gpt-image-2.5-flare");
                    var response = execution.execute(request, body);
                    log.info("OpenAI Image API HTTP status={}", response.getStatusCode().value());
                    return response;
                }).build();
        JsonNode response;
        try {
            response = client.post().uri("/images/generations").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", "gpt-image-2.5-flare", "n", 1, "size", "1536x1024",
                            "output_format", "png", "quality", "medium", "prompt", prompt))
                    .retrieve().body(JsonNode.class);
        } catch (RestClientResponseException | ResourceAccessException exception) {
            throw failed();
        } catch (RestClientException exception) {
            throw invalidResponse();
        }
        remaining(deadline);
        byte[] png = decodePng(response);
        remaining(deadline);
        AgentImage image = storage.save(png);
        log.info("Generated fence image saved: imageId={}", image.id());
        return image;
    }

    private static String argument(JsonNode arguments, String field, boolean required, int limit) {
        if (arguments == null || !arguments.isObject()) throw invalidArguments();
        JsonNode value = arguments.path(field);
        if (!required && (value.isMissingNode() || value.isNull())) return "";
        if (!value.isString() || (required && value.asString().isBlank()) || value.asString().length() > limit) {
            throw invalidArguments();
        }
        return value.asString();
    }

    private static byte[] decodePng(JsonNode response) {
        boolean hasData = response != null && response.path("data").isArray();
        int dataCount = hasData ? response.path("data").size() : 0;
        JsonNode encoded = hasData && dataCount > 0 ? response.path("data").get(0).path("b64_json") : null;
        log.info("Image API response: dataPresent={}, dataCount={}, b64Present={}, b64Length={}",
                hasData, dataCount, encoded != null && encoded.isString() && !encoded.asString().isBlank(),
                encoded != null && encoded.isString() ? encoded.asString().length() : 0);
        if (!hasData || dataCount != 1) throw invalidResponse();
        if (!encoded.isString() || encoded.asString().isBlank() || encoded.asString().length() > MAX_PNG_BYTES * 4 / 3 + 4) {
            throw invalidResponse();
        }
        try {
            byte[] png = Base64.getDecoder().decode(encoded.asString());
            if (png.length > MAX_PNG_BYTES || png.length < PNG_SIGNATURE.length
                    || !Arrays.equals(PNG_SIGNATURE, Arrays.copyOf(png, PNG_SIGNATURE.length))) throw invalidResponse();
            try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(png))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw invalidResponse();
                var reader = readers.next();
                try {
                    reader.setInput(input);
                    if (!"png".equalsIgnoreCase(reader.getFormatName()) || reader.getWidth(0) > 4096 || reader.getHeight(0) > 4096
                            || reader.read(0) == null) throw invalidResponse();
                } finally { reader.dispose(); }
            }
            return png;
        } catch (IOException | IllegalArgumentException exception) {
            throw invalidResponse();
        }
    }

    private static JsonNode normalizeArguments(JsonNode arguments) {
        if (arguments != null && arguments.isTextual()) {
            try { return new JsonMapper().readTree(arguments.asString()); }
            catch (RuntimeException ignored) { throw invalidArguments(); }
        }
        return arguments;
    }

    private static String safeValue(String value) {
        if (value == null || value.isBlank()) return "blank";
        return value.length() > 80 ? value.substring(0, 80) + "…" : value;
    }

    private static Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw failed();
        return Duration.ofNanos(nanos);
    }

    private static AgentException failed() {
        return new AgentException(HttpStatus.BAD_GATEWAY, "IMAGE_GENERATION_FAILED", "OpenAI image generation failed or timed out.");
    }
    private static AgentException invalidResponse() {
        return new AgentException(HttpStatus.BAD_GATEWAY, "IMAGE_RESPONSE_INVALID", "OpenAI returned invalid PNG image data.");
    }
    private static AgentException invalidArguments() {
        return new AgentException(HttpStatus.BAD_GATEWAY, "INVALID_AGENT_TOOL_ARGUMENTS", "Invalid fence image arguments.");
    }
}
