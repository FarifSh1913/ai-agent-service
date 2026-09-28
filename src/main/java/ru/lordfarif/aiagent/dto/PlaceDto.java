package ru.lordfarif.aiagent.dto;

import java.math.BigDecimal;

public record PlaceDto(String title, String type, String address, Double lat, Double lon,
                       Double distanceMeters, BigDecimal estimatedPrice, Boolean openAtRequestedTime,
                       String reason, String source, String sourceUrl) {}
