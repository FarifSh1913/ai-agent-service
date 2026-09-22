package ru.lordfarif.aiagent.dto;

import jakarta.validation.Validation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AgentChatRequestTest {
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "\t\n"})
    void requiresNonBlankMessage(String message) {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(new AgentChatRequest(message, null))).hasSize(1);
        }
    }

    @Test void acceptsText() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(new AgentChatRequest("У меня бюджет 30000 рублей", null))).isEmpty();
        }
    }
}
