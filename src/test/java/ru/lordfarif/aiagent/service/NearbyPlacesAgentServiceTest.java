package ru.lordfarif.aiagent.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import ru.lordfarif.aiagent.config.OpenAiProperties;
import ru.lordfarif.aiagent.dto.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class NearbyPlacesAgentServiceTest {
    OpenAiAgentClient client;
    NearbyPlacesAgentService service;
    NearbyPlacesResponse upstream;

    @BeforeEach void setup() {
        client = mock(OpenAiAgentClient.class);
        service = new NearbyPlacesAgentService(client, new OpenAiProperties());
        when(client.runAgent(anyString(), any(), eq(NearbyPlacesResponse.class), any()))
                .thenAnswer(invocation -> {
                    BiFunction<String, NearbyPlacesResponse, NearbyPlacesResponse> processor = invocation.getArgument(3);
                    return processor.apply("sess_test", upstream);
                });
    }

    NearbyPlacesRequest request(Double lat, Double lon) {
        return new NearbyPlacesRequest("Москва", LocalDate.of(2026, 9, 29), "18:00", "23:00",
                new BigDecimal("20000"), "couple", List.of("restaurant"),
                new EventDto(1L, "Event", "Address", lat, lon, BigDecimal.ZERO, "KudaGo"));
    }

    PlaceDto place(String url, Boolean open, Double lat, Double lon, Double distance) {
        return new PlaceDto("  Кафе  ", "  restaurant  ", "  Адрес  ", lat, lon, distance,
                new BigDecimal("2500.50"), open, "  Для ужина  ", "  Сайт  ", url);
    }

    NearbyPlacesResponse normalize(PlaceDto... places) {
        upstream = new NearbyPlacesResponse(Arrays.asList(places));
        return service.findPlaces(request(0.0, 0.0));
    }

    @Test void unwrapsMarkdownSourceUrl() {
        var result = normalize(place("  [https://example.com](https://example.com)  ", true, null, null, null));
        assertThat(result.places().getFirst().sourceUrl()).isEqualTo("https://example.com");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"https://example.com", "https://example.com/path?q=value#anchor"})
    void preservesPlainAndNullUrls(String url) {
        assertThat(normalize(place(url, true, null, null, null)).places().getFirst().sourceUrl()).isEqualTo(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "[broken](", "[link](javascript:alert(1))", "not a URL"})
    void invalidOptionalUrlsBecomeNull(String url) {
        assertThat(normalize(place(url, true, null, null, null)).places().getFirst().sourceUrl()).isNull();
    }

    @Test void removesClosedAndNullPlacesButKeepsUnknownHours(CapturedOutput output) {
        var unknown = place(null, null, null, null, null);
        var result = normalize(place(null, false, null, null, null), null, unknown);
        assertThat(result.places()).hasSize(1);
        assertThat(result.places().getFirst().openAtRequestedTime()).isNull();
        assertThat(output).contains("sessionId=sess_test", "placesBefore=3", "placesAfter=1")
                .doesNotContain("Для ужина", "Authorization");
    }

    @Test void allowsEmptyListAfterFiltering() {
        assertThat(normalize(place(null, false, null, null, null)).places()).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(doubles = {0.0, 999999.0})
    void recalculatesDistanceEvenIfAgentProvidedValue(Double agentDistance) {
        var result = normalize(place(null, true, 0.0, 1.0, agentDistance));
        assertThat(result.places().getFirst().distanceMeters()).isEqualTo(111195.0);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(doubles = {0.0, 42.0})
    void preservesAgentDistanceWithoutPlaceCoordinates(Double distance) {
        assertThat(normalize(place(null, true, null, null, distance)).places().getFirst().distanceMeters())
                .isEqualTo(distance);
    }

    @Test void incompleteOrInvalidCoordinatesDoNotCreateDistance() {
        for (Double[] coords : List.of(new Double[]{null, 1.0}, new Double[]{1.0, null},
                new Double[]{91.0, 0.0}, new Double[]{Double.NaN, 0.0}, new Double[]{0.0, Double.POSITIVE_INFINITY})) {
            assertThat(normalize(place(null, true, coords[0], coords[1], null)).places().getFirst().distanceMeters()).isNull();
        }
        upstream = new NearbyPlacesResponse(List.of(place(null, true, 0.0, 1.0, 42.0)));
        assertThat(service.findPlaces(request(null, 0.0)).places().getFirst().distanceMeters()).isEqualTo(42.0);
    }

    @Test void normalizesNullList() {
        upstream = new NearbyPlacesResponse(null);
        assertThat(service.findPlaces(request(0.0, 0.0)).places()).isEmpty();
    }

    @Test void rejectsNullResponse() {
        upstream = null;
        assertThatThrownBy(() -> service.findPlaces(request(0.0, 0.0)))
                .isInstanceOfSatisfying(AgentException.class, error -> assertThat(error.getCode()).isEqualTo("INVALID_AGENT_JSON"));
    }

    @Test void trimsStringsAndPreservesPriceAndCoordinates(CapturedOutput output) {
        var input = place(" [link](https://example.com) ", true, 0.0, 1.0, null);
        var result = normalize(input).places().getFirst();
        assertThat(result.title()).isEqualTo("Кафе");
        assertThat(result.type()).isEqualTo("restaurant");
        assertThat(result.address()).isEqualTo("Адрес");
        assertThat(result.reason()).isEqualTo("Для ужина");
        assertThat(result.source()).isEqualTo("Сайт");
        assertThat(result.estimatedPrice()).isEqualTo(input.estimatedPrice());
        assertThat(result.lat()).isEqualTo(input.lat());
        assertThat(result.lon()).isEqualTo(input.lon());
        assertThat(output).contains("sourceUrlsNormalized=1", "distancesRecalculated=1");
    }

    @Test void blankOptionalStringsBecomeNullAndBlankTitleStaysString() {
        var result = normalize(new PlaceDto("  ", " ", " ", null, null, null, null, null, " ", " ", " "))
                .places().getFirst();
        assertThat(result.title()).isEmpty();
        assertThat(result.address()).isNull();
        assertThat(result.source()).isNull();
        assertThat(result.sourceUrl()).isNull();
        assertThat(result.estimatedPrice()).isNull();
        assertThat(result.lat()).isNull();
        assertThat(result.lon()).isNull();
        assertThat(result.distanceMeters()).isNull();
    }

    @Test void haversineHandlesIdenticalAntipodalAndDatelineCoordinates() {
        assertThat(GeoDistance.meters(55.7, 37.6, 55.7, 37.6)).isZero();
        assertThat(GeoDistance.meters(0, 0, 0, 180)).isEqualTo(20015087.0);
        assertThat(GeoDistance.meters(0, 179, 0, -179)).isEqualTo(222390.0);
    }
}
