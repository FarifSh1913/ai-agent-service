package ru.lordfarif.aiagent.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import ru.lordfarif.aiagent.config.OpenAiProperties;
import ru.lordfarif.aiagent.dto.EventDto;
import ru.lordfarif.aiagent.dto.NearbyPlacesRequest;
import ru.lordfarif.aiagent.dto.NearbyPlacesResponse;
import ru.lordfarif.aiagent.dto.PlaceDto;

@Service
public class NearbyPlacesAgentService {
    private static final Logger log = LoggerFactory.getLogger(NearbyPlacesAgentService.class);
    private static final Pattern MARKDOWN_LINK = Pattern.compile("^\\[[^\\[\\]\\r\\n]*\\]\\((https?://[^\\s()]+)\\)$");
    private final OpenAiAgentClient client;
    private final OpenAiProperties properties;

    public NearbyPlacesAgentService(OpenAiAgentClient client, OpenAiProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public NearbyPlacesResponse findPlaces(NearbyPlacesRequest request) {
        return client.runAgent(properties.getAgents().getNearbyPlaces().getId(), request, NearbyPlacesResponse.class,
                (sessionId, response) -> normalize(sessionId, request.event(), response));
    }

    private NearbyPlacesResponse normalize(String sessionId, EventDto event, NearbyPlacesResponse response) {
        if (response == null) {
            throw new AgentException(HttpStatus.BAD_GATEWAY, "INVALID_AGENT_JSON", "Agent returned an invalid JSON result.");
        }
        var places = new ArrayList<PlaceDto>();
        int normalizedUrls = 0;
        int recalculatedDistances = 0;
        for (PlaceDto place : response.places()) {
            if (place == null || Boolean.FALSE.equals(place.openAtRequestedTime())) continue;
            String sourceUrl = normalizeUrl(place.sourceUrl());
            if (!Objects.equals(sourceUrl, place.sourceUrl())) normalizedUrls++;
            Double distance = place.distanceMeters();
            if (event != null && GeoDistance.hasCoordinates(event.lat(), event.lon())
                    && GeoDistance.hasCoordinates(place.lat(), place.lon())) {
                distance = GeoDistance.meters(event.lat(), event.lon(), place.lat(), place.lon());
                recalculatedDistances++;
            }
            places.add(new PlaceDto(trim(place.title()), trim(place.type()), optional(place.address()),
                    place.lat(), place.lon(), distance, place.estimatedPrice(), place.openAtRequestedTime(),
                    trim(place.reason()), optional(place.source()), sourceUrl));
        }
        log.info("Nearby places normalized: sessionId={}, placesBefore={}, placesAfter={}, sourceUrlsNormalized={}, distancesRecalculated={}",
                sessionId, response.places().size(), places.size(), normalizedUrls, recalculatedDistances);
        return new NearbyPlacesResponse(places);
    }

    private static String normalizeUrl(String value) {
        String url = optional(value);
        if (url == null) return null;
        var matcher = MARKDOWN_LINK.matcher(url);
        if (matcher.matches()) url = matcher.group(1);
        // An unrecognized Markdown fragment or non-URL is not a confirmed source URL.
        try {
            URI uri = new URI(url);
            if (("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getRawUserInfo() == null) return url;
        } catch (URISyntaxException ignored) {
            // Do not log source text; an invalid optional URL is represented as null.
        }
        return null;
    }

    private static String trim(String value) { return value == null ? null : value.trim(); }

    private static String optional(String value) {
        String trimmed = trim(value);
        return trimmed == null || trimmed.isEmpty() ? null : trimmed;
    }
}
