package ru.lordfarif.aiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

@Validated
@ConfigurationProperties(prefix = "openai")
public class OpenAiProperties {
    private String apiKey;
    private String agentId;
    @Valid
    private final Agents agents = new Agents();
    @Valid
    private final Polling polling = new Polling();

    public Agents getAgents() { return agents; }
    public Polling getPolling() { return polling; }

    public static class Agents {
        @Valid
        private final NearbyPlaces nearbyPlaces = new NearbyPlaces();
        public NearbyPlaces getNearbyPlaces() { return nearbyPlaces; }
    }

    public static class NearbyPlaces {
        private String id = "agent_2fc10e837c4b4ef3bab6cb6b0659b6d2f8a8a83acb5f4cdcb6";
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
    }

    public static class Polling {
        @Min(1)
        private long intervalMs = 750;
        @Min(1)
        private long timeoutMs = 60000;
        public long getIntervalMs() { return intervalMs; }
        public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }
        public long getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
    }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
}
