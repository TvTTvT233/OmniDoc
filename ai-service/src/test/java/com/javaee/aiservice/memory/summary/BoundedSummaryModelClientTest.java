package com.javaee.aiservice.memory.summary;

import com.javaee.aiservice.agent.ChatService;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BoundedSummaryModelClientTest {

    @Test
    void modelTimeoutIsIndependentAndFailsClosed() {
        ChatService chatService = mock(ChatService.class);
        when(chatService.callChatApiWithModelCode(any(), any())).thenAnswer(ignored -> {
            Thread.sleep(5_000);
            return "never returned";
        });
        BoundedSummaryModelClient client = new BoundedSummaryModelClient(chatService);
        try {
            assertThatThrownBy(() -> client.generate("prompt", "test-model", Duration.ofMillis(20)))
                    .isInstanceOf(SummaryGenerationException.class)
                    .hasMessageContaining("timed out");
        } finally {
            client.destroy();
        }
    }
}
