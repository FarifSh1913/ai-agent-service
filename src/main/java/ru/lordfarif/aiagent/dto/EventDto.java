package ru.lordfarif.aiagent.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record EventDto(
        @NotNull @Positive Long id,
        @NotBlank String title,
        @NotBlank String address,
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double lon,
        @NotNull @PositiveOrZero BigDecimal price,
        @NotBlank String source) {}
