package ru.lordfarif.aiagent.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.HttpStatus;
import ru.lordfarif.aiagent.config.OpenAiProperties;
import ru.lordfarif.aiagent.dto.*;
import ru.lordfarif.aiagent.service.*;
import java.util.List;
import java.math.BigDecimal;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(NearbyPlacesController.class)
@Import(NearbyPlacesAgentService.class)
@EnableConfigurationProperties(OpenAiProperties.class)
class NearbyPlacesControllerTest {
    @Autowired MockMvc mvc;
    @Autowired OpenAiProperties properties;
    @MockitoBean OpenAiAgentClient client;

    static final String REQUEST = """
            {"city":"Москва","date":"2026-09-29","timeFrom":"18:00","timeTo":"23:00",
             "budget":20000,"company":"couple","preferences":["cafe","restaurant"],
             "event":{"id":226684,"title":"мюзикл «Плакса»","address":"Пушкинская пл., д. 2",
                      "lat":55.7658,"lon":37.6050,"price":1000,"source":"KudaGo"}}
            """;

    @Test void routesToConfiguredAgentAndReturnsOnlyBusinessResult() throws Exception {
        when(client.runAgent(eq(properties.getAgents().getNearbyPlaces().getId()), any(NearbyPlacesRequest.class),
                eq(NearbyPlacesResponse.class), any())).thenAnswer(invocation -> {
            NearbyPlacesRequest request = invocation.getArgument(1);
            assertThat(request.date()).isEqualTo(java.time.LocalDate.of(2026, 9, 29));
            assertThat(request.event().id()).isEqualTo(226684L);
            assertThat(request.budget()).isEqualByComparingTo("20000");
            return processed(invocation, new NearbyPlacesResponse(List.of(new PlaceDto("Кафе", "cafe", "Москва", 55.7, 37.6,
                    20.0, new BigDecimal("2500"), true, "Ужин", "Сайт", "https://example.com"))));
        });
        mvc.perform(post("/api/agents/nearby-places").contentType("application/json").content(REQUEST))
                .andExpect(status().isOk()).andExpect(jsonPath("$.places[0].title").value("Кафе"))
                .andExpect(jsonPath("$.places[0].estimatedPrice").value(2500))
                .andExpect(jsonPath("$.places[0].openAtRequestedTime").value(true))
                .andExpect(jsonPath("$.*").value(org.hamcrest.Matchers.hasSize(1)));
        verify(client).runAgent(eq(properties.getAgents().getNearbyPlaces().getId()), any(NearbyPlacesRequest.class),
                eq(NearbyPlacesResponse.class), any());
    }

    @Test void returnsEmptyPlaces() throws Exception {
        when(client.runAgent(anyString(), any(), eq(NearbyPlacesResponse.class), any()))
                .thenAnswer(invocation -> processed(invocation, new NearbyPlacesResponse(List.of())));
        mvc.perform(post("/api/agents/nearby-places").contentType("application/json").content(REQUEST))
                .andExpect(status().isOk()).andExpect(content().json("{\"places\":[]}"));
    }

    private NearbyPlacesResponse processed(org.mockito.invocation.InvocationOnMock invocation, NearbyPlacesResponse response) {
        java.util.function.BiFunction<String, NearbyPlacesResponse, NearbyPlacesResponse> processor = invocation.getArgument(3);
        return processor.apply("sess_1", response);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"places\":null}", "{\"places\":[null]}",
            "{\"places\":[{\"openAtRequestedTime\":false}]}"})
    void normalizesNullableAndFilteredListsToEmptyJson(String json) throws Exception {
        var response = new tools.jackson.databind.json.JsonMapper().readValue(json, NearbyPlacesResponse.class);
        when(client.runAgent(anyString(), any(), eq(NearbyPlacesResponse.class), any()))
                .thenAnswer(invocation -> processed(invocation, response));
        mvc.perform(post("/api/agents/nearby-places").contentType("application/json").content(REQUEST))
                .andExpect(status().isOk()).andExpect(content().json("{\"places\":[]}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{", "null", "invalidTime", "invalidCoordinates", "negativeBudget", "missingEvent", "invalidDate"})
    void rejectsInvalidRequests(String value) throws Exception {
        String body = switch (value) {
            case "invalidTime" -> REQUEST.replace("18:00", "25:00");
            case "invalidCoordinates" -> REQUEST.replace("55.7658", "95.0");
            case "negativeBudget" -> REQUEST.replace("20000", "-1");
            case "missingEvent" -> REQUEST.replace("\"event\"", "\"other\"");
            case "invalidDate" -> REQUEST.replace("2026-09-29", "not-a-date");
            default -> value;
        };
        mvc.perform(post("/api/agents/nearby-places").contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(ints = {502, 503, 504})
    void preservesBackendErrorStatus(int status) throws Exception {
        when(client.runAgent(anyString(), any(), eq(NearbyPlacesResponse.class), any()))
                .thenThrow(new AgentException(HttpStatus.valueOf(status), "TEST_ERROR", "Safe error"));
        mvc.perform(post("/api/agents/nearby-places").contentType("application/json").content(REQUEST))
                .andExpect(status().is(status)).andExpect(jsonPath("$.code").value("TEST_ERROR"));
    }
}
