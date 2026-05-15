package org.taniwha.service.enrichment;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ScaleInferenceServiceTest {

    @Test
    void inferLabels_returnsEmptyForMissingInputsOrChatModel() {
        ScaleInferenceService service = new ScaleInferenceService();

        assertEquals(Map.of(), service.inferLabels(null, List.of("1")));
        assertEquals(Map.of(), service.inferLabels("ability", null));
        assertEquals(Map.of(), service.inferLabels("ability", List.of()));
        assertEquals(Map.of(), service.inferLabels("ability", List.of("0", "10")));
    }

    @Test
    void inferLabels_parsesJsonObjectResponse() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse response = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
        when(response.getResult().getOutput().getText())
                .thenReturn("{\"0\":\"no ability\",\"10\":\"full ability\",\"x\":null}");
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        ScaleInferenceService service = new ScaleInferenceService();
        ReflectionTestUtils.setField(service, "chatModel", chatModel);

        assertEquals(
                Map.of("0", "no ability", "10", "full ability", "x", ""),
                service.inferLabels("mobility", List.of("0", "10"))
        );
    }

    @Test
    void inferLabels_extractsJsonObjectFromWrappedResponse() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse response = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
        when(response.getResult().getOutput().getText())
                .thenReturn("Labels: {\"1\":\"mild\",\"5\":\"severe\"}");
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        ScaleInferenceService service = new ScaleInferenceService();
        ReflectionTestUtils.setField(service, "chatModel", chatModel);

        assertEquals(
                Map.of("1", "mild", "5", "severe"),
                service.inferLabels("severity", List.of("1", "5"))
        );
    }

    @Test
    void inferLabels_returnsEmptyWhenChatModelFails() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("llm down"));

        ScaleInferenceService service = new ScaleInferenceService();
        ReflectionTestUtils.setField(service, "chatModel", chatModel);

        assertEquals(Map.of(), service.inferLabels("severity", List.of("1")));
    }
}
