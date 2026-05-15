package org.taniwha.service.enrichment;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ParaphraseServiceTest {

    @Test
    void paraphrase_returnsEmptyForBlankInputOrMissingChatModel() {
        ParaphraseService service = new ParaphraseService();

        assertEquals(List.of(), service.paraphrase(null, 3));
        assertEquals(List.of(), service.paraphrase("   ", 3));
        assertEquals(List.of(), service.paraphrase("blood pressure", 3));
    }

    @Test
    void paraphrase_parsesJsonArrayResponse() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse response = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
        when(response.getResult().getOutput().getText())
                .thenReturn("[\"arterial pressure\",\"bp reading\",7]");
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        ParaphraseService service = new ParaphraseService();
        ReflectionTestUtils.setField(service, "chatModel", chatModel);

        assertEquals(List.of("arterial pressure", "bp reading"),
                service.paraphrase("blood pressure", 3));
    }

    @Test
    void paraphrase_extractsJsonArrayFromWrappedResponse() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse response = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
        when(response.getResult().getOutput().getText())
                .thenReturn("Here you go: [\"heart rate\",\"pulse\"]");
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        ParaphraseService service = new ParaphraseService();
        ReflectionTestUtils.setField(service, "chatModel", chatModel);

        assertEquals(List.of("heart rate", "pulse"),
                service.paraphrase("hr", 2));
    }

    @Test
    void paraphrase_returnsEmptyWhenChatModelFails() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("llm down"));

        ParaphraseService service = new ParaphraseService();
        ReflectionTestUtils.setField(service, "chatModel", chatModel);

        assertEquals(List.of(), service.paraphrase("blood pressure", 3));
    }
}
