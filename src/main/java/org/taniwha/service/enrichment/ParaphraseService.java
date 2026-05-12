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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Uses the configured chat model to generate alternate lookup phrases.
@Service
public class ParaphraseService {
    private static final Logger logger = LoggerFactory.getLogger(ParaphraseService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired(required = false)
    private ChatModel chatModel;

    public List<String> paraphrase(String text, int max) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        if (chatModel == null) {
            logger.debug("[Paraphrase] ChatModel not available; no LLM-based generation");
            return out;
        }

        try {
            return generateParaphrases(text, max);
        } catch (Exception e) {
            logger.warn("[Paraphrase] LLM paraphrase generation failed: {}", e.getMessage());
            return out;
        }
    }

    private List<String> generateParaphrases(String text, int max) {
        String template = """
Generate up to {max} concise paraphrases (synonyms or alternative phrasings) of: "{text}"

Return ONLY a valid JSON array of strings, one per paraphrase.
Example: ["alternative phrasing 1", "alternative phrasing 2"]

Respond with ONLY the JSON array and no other text.""";

        PromptTemplate promptTemplate = new PromptTemplate(template);
        Map<String, Object> params = Map.of(
                "text", text,
                "max", Math.max(1, max)
        );
        Prompt prompt = promptTemplate.create(params);

        ChatResponse response = chatModel.call(prompt);
        String content = response.getResult().getOutput().getText().trim();

        logger.debug("[Paraphrase] LLM raw response: {}", content);

        List<String> result = parseJsonArray(content);
        if (!result.isEmpty()) {
            logger.debug("[Paraphrase] Generated {} paraphrase(s)", result.size());
        }
        return result;
    }

    private List<String> parseJsonArray(String response) {
        List<String> result = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(response);
            if (root.isArray()) {
                for (JsonNode node : root) {
                    if (node.isTextual()) {
                        result.add(node.asText());
                    }
                }
                return result;
            }
        } catch (Exception e1) {
            int start = response.indexOf('[');
            int end = response.lastIndexOf(']');
            if (start >= 0 && end > start) {
                String jsonStr = response.substring(start, end + 1);
                try {
                    JsonNode root = MAPPER.readTree(jsonStr);
                    if (root.isArray()) {
                        for (JsonNode node : root) {
                            if (node.isTextual()) {
                                result.add(node.asText());
                            }
                        }
                        return result;
                    }
                } catch (Exception e2) {
                    logger.debug("[Paraphrase] Failed to parse JSON array: {}", e2.getMessage());
                }
            }
        }
        return result;
    }
}
