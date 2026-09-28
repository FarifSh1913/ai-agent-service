package ru.lordfarif.aiagent.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import ru.lordfarif.aiagent.dto.NearbyPlacesRequest;
import ru.lordfarif.aiagent.dto.NearbyPlacesResponse;
import ru.lordfarif.aiagent.service.NearbyPlacesAgentService;

@RestController
@RequestMapping("/api/agents/nearby-places")
public class NearbyPlacesController {
    private final NearbyPlacesAgentService service;

    public NearbyPlacesController(NearbyPlacesAgentService service) { this.service = service; }

    @PostMapping
    public NearbyPlacesResponse findPlaces(@Valid @RequestBody NearbyPlacesRequest request) {
        return service.findPlaces(request);
    }
}
