package ru.lordfarif.aiagent.dto;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

public record NearbyPlacesResponse(List<PlaceDto> places) {
    public NearbyPlacesResponse {
        // Keep null entries until post-processing so its input count reflects the parsed response.
        places = places == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(places));
    }
}
