package org.taniwha.service.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Uses the configured chat model to infer short labels for scale values.
@Service
public class ScaleInferenceService {

    private static final Logger logger = LoggerFactory.getLogger(ScaleInferenceService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired(required = false)
    private ChatModel chatModel;

    public Map<String, String> inferLabels(String columnName, List<String> sampleValues) {
        if (columnName == null || sampleValues == null || sampleValues.isEmpty()) {
            return Map.of();
        }

        if (chatModel == null) {
            logger.debug("[ScaleInference] ChatModel not available; no LLM-based inference");
            return Map.of();
        }

        try {
            return callLlmForLabels(columnName, sampleValues);
        } catch (Exception e) {
            logger.warn("[ScaleInference] LLM inference failed: {}", e.getMessage());
            return Map.of();
        }
    }

    private Map<String, String> callLlmForLabels(String columnName, List<String> sampleValues) {
        String template = """
Given the column name: "{columnName}" and these sample numeric/categorical values: {sampleValues}

Infer short semantic label phrases (3 words or less) that describe what each value represents in context.
Return ONLY a valid JSON object mapping each value (as string key) to its label (as string value).
Example entries: key "0" maps to "no ability", key "5" maps to "partial ability", key "10" maps to "full ability".

Respond with ONLY the JSON object and no other text.""";

        PromptTemplate promptTemplate = new PromptTemplate(template);
        Map<String, Object> params = Map.of(
                "columnName", columnName,
                "sampleValues", sampleValues.toString()
        );
        Prompt prompt = promptTemplate.create(params);

        ChatResponse response = chatModel.call(prompt);
        String content = response.getResult().getOutput().getText().trim();

        logger.debug("[ScaleInference] LLM raw response: {}", content);

        Map<String, String> result = parseJsonResponse(content);
        if (!result.isEmpty()) {
            logger.debug("[ScaleInference] Inferred labels: {}", result);
        }
        return result;
    }

    private Map<String, String> parseJsonResponse(String response) {
        try {
            JsonNode node = MAPPER.readTree(response);
            if (node.isObject()) {
                Map<String, String> result = new HashMap<>();
                node.fields().forEachRemaining(e -> 
                    result.put(e.getKey(), e.getValue().asText(""))
                );
                return result;
            }
        } catch (Exception e1) {
            int start = response.indexOf('{');
            int end = response.lastIndexOf('}');
            if (start >= 0 && end > start) {
                String jsonStr = response.substring(start, end + 1);
                try {
                    JsonNode node = MAPPER.readTree(jsonStr);
                    if (node.isObject()) {
                        Map<String, String> result = new HashMap<>();
                        node.fields().forEachRemaining(e -> 
                            result.put(e.getKey(), e.getValue().asText(""))
                        );
                        return result;
                    }
                } catch (Exception e2) {
                    logger.debug("[ScaleInference] Failed to parse JSON substring: {}", e2.getMessage());
                }
            }
        }
        return Map.of();
    }
}
