package ru.lordfarif.aiagent.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record NearbyPlacesRequest(
        @NotBlank String city,
        @NotNull LocalDate date,
        @NotBlank @Pattern(regexp = "(?:[01][0-9]|2[0-3]):[0-5][0-9]") String timeFrom,
        @NotBlank @Pattern(regexp = "(?:[01][0-9]|2[0-3]):[0-5][0-9]") String timeTo,
        @NotNull @PositiveOrZero BigDecimal budget,
        @NotBlank String company,
        @NotNull List<@NotBlank String> preferences,
        @NotNull @Valid EventDto event) {}
