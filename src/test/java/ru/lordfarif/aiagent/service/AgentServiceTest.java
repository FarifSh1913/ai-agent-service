package ru.lordfarif.aiagent.service;

import org.junit.jupiter.api.Test;
import ru.lordfarif.aiagent.dto.AgentChatResponse;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentServiceTest {
    @Test void delegatesMessageAndReturnsAnswer() {
        var client = mock(OpenAiAgentClient.class);
        when(client.chat("message", "sess_1")).thenReturn(new AgentChatResponse("sess_1", "answer"));
        assertThat(new AgentService(client).chat("message", "sess_1")).isEqualTo(new AgentChatResponse("sess_1", "answer"));
        verify(client).chat("message", "sess_1");
        verifyNoMoreInteractions(client);
    }

    @Test void propagatesClientFailure() {
        var client = mock(OpenAiAgentClient.class);
        var failure = new IllegalStateException("failure");
        when(client.chat("message", "sess_1")).thenThrow(failure);
        assertThatThrownBy(() -> new AgentService(client).chat("message", "sess_1")).isSameAs(failure);
    }
}
