package org.taniwha.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.taniwha.config.RestTemplateConfig;
import org.taniwha.util.StringUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generates human-readable clinical descriptions for harmonized data elements.
 *
 * <p>Two modes are supported:
 * <ol>
 *   <li><b>LLM mode</b> ({@code description.generator.llm.enabled=true}): Calls an
 *       OpenAI-compatible chat completion API. Works with local Ollama models
 *       (recommended: {@code meditron}, {@code medllama2}, or {@code llama3.2}) as well
 *       as cloud providers such as OpenAI ({@code gpt-4o-mini}, {@code gpt-4o}).</li>
 *   <li><b>Template mode</b> (default): Builds concise, informative descriptions from
 *       available metadata without any network call.</li>
 * </ol>
 *
 * <h3>Recommended medical LLMs (via Ollama)</h3>
 * <ul>
 *   <li>{@code meditron} – EPFL model trained on PubMed abstracts and clinical guidelines
 *       (7 B parameters); excellent for clinical NLP tasks.</li>
 *   <li>{@code medllama2} – LLaMA 2 fine-tuned on medical QA datasets.</li>
 *   <li>{@code llama3.2} – General-purpose; combine with the built-in medical system
 *       prompt for strong biomedical descriptions.</li>
 * </ul>
 *
 * <h3>Quick-start with Ollama</h3>
 * <pre>
 *   # pull a medical model
 *   ollama pull meditron
 *
 *   # enable in application.properties
 *   description.generator.llm.enabled=true
 *   description.generator.llm.base-url=http://localhost:11434
 *   description.generator.llm.model=meditron
 * </pre>
 */
@Service
public class DescriptionGeneratorService {

    private static final Logger logger = LoggerFactory.getLogger(DescriptionGeneratorService.class);

    private static final String SYSTEM_PROMPT =
            "You are a medical informatics expert specializing in clinical data harmonization "
            + "and biomedical terminology. Generate concise, clinically accurate descriptions "
            + "for medical data elements. Use appropriate medical terminology where relevant. "
            + "Keep descriptions to 1-2 sentences. Reply with the description only – no "
            + "preamble, no bullet points, no extra formatting.";

    private static final int MAX_SAMPLE_VALUES = 8;
    private static final int MAX_TOKENS = 120;

    /** Enable LLM-based description generation (default: false). */
    @Value("${description.generator.llm.enabled:false}")
    private boolean llmEnabled;

    /**
     * Base URL of the OpenAI-compatible inference endpoint.
     * For Ollama: {@code http://localhost:11434} (default).
     * For OpenAI:  {@code https://api.openai.com}.
     */
    @Value("${description.generator.llm.base-url:http://localhost:11434}")
    private String llmBaseUrl;

    /**
     * Model name to use for chat completions.
     * Recommended medical models: {@code meditron}, {@code medllama2}.
     * General-purpose alternative: {@code llama3.2}.
     */
    @Value("${description.generator.llm.model:llama3.2}")
    private String llmModel;

    /** API key sent as Bearer token. Leave blank for local Ollama. */
    @Value("${description.generator.llm.api-key:}")
    private String llmApiKey;

    private final RestTemplateConfig restTemplateConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public DescriptionGeneratorService(RestTemplateConfig restTemplateConfig) {
        this.restTemplateConfig = restTemplateConfig;
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Generates a concept-level description for a harmonized mapping
     * (i.e. what the overall data element represents clinically).
     *
     * @param conceptName  sanitized union key (e.g. {@code "age"}, {@code "diagnosis_code"})
     * @param detectedType detected data type ({@code integer}, {@code double}, {@code date},
     *                     {@code categorical}, …)
     * @param sampleValues representative raw values from the source columns
     * @return a non-null, non-empty description string
     */
    public String generateConceptDescription(
            String conceptName,
            String detectedType,
            List<String> sampleValues) {

        String key = "concept:" + safe(conceptName) + "|" + safe(detectedType);
        return cache.computeIfAbsent(key,
                k -> generateConceptInternal(conceptName, detectedType, sampleValues));
    }

    /**
     * Generates a value-level description for a single harmonized value within a mapping.
     *
     * @param conceptName  sanitized union key
     * @param valueName    the specific harmonized value (e.g. {@code "male"}, {@code "7"})
     * @param mappingType  one of {@code ordinal}, {@code range}, {@code categorical},
     *                     {@code enum}, {@code clustered}
     * @param detectedType detected data type
     * @param sampleValues representative raw values from the source columns
     * @param rangeInfo    optional range annotation (e.g. {@code "file:col=[0,100]"}); may be null
     * @return a non-null, non-empty description string
     */
    public String generateValueDescription(
            String conceptName,
            String valueName,
            String mappingType,
            String detectedType,
            List<String> sampleValues,
            String rangeInfo) {

        String key = "value:" + safe(conceptName) + "|" + safe(valueName) + "|" + safe(mappingType);
        return cache.computeIfAbsent(key,
                k -> generateValueInternal(conceptName, valueName, mappingType,
                                           detectedType, sampleValues, rangeInfo));
    }

    // ------------------------------------------------------------------
    // Concept-level
    // ------------------------------------------------------------------

    private String generateConceptInternal(
            String conceptName, String detectedType, List<String> sampleValues) {

        if (llmEnabled) {
            try {
                String prompt = buildConceptPrompt(conceptName, detectedType, sampleValues);
                String result = callLlm(prompt);
                if (result != null && !result.isEmpty()) {
                    return result;
                }
            } catch (Exception e) {
                logger.warn("LLM concept description failed for '{}': {}", conceptName, e.getMessage());
            }
        }
        return templateConceptDescription(conceptName, detectedType);
    }

    private String buildConceptPrompt(
            String conceptName, String detectedType, List<String> sampleValues) {

        StringBuilder sb = new StringBuilder();
        sb.append("Describe this clinical data element in 1-2 sentences:\n");
        sb.append("Name: ").append(conceptName).append("\n");
        sb.append("Data type: ").append(detectedType).append("\n");
        if (sampleValues != null && !sampleValues.isEmpty()) {
            List<String> limited = limit(sampleValues, MAX_SAMPLE_VALUES);
            sb.append("Sample values: ").append(String.join(", ", limited)).append("\n");
        }
        sb.append("Provide a concise clinical description only.");
        return sb.toString();
    }

    private String templateConceptDescription(String conceptName, String detectedType) {
        String h = StringUtil.humanize(conceptName);
        if ("date".equalsIgnoreCase(detectedType)) {
            return StringUtil.capitalize(h) + " date field harmonized across source datasets.";
        }
        if ("integer".equalsIgnoreCase(detectedType) || "double".equalsIgnoreCase(detectedType)) {
            return StringUtil.capitalize(h) + " numeric field harmonized across source datasets.";
        }
        return StringUtil.capitalize(h) + " clinical data element harmonized across source datasets.";
    }

    // ------------------------------------------------------------------
    // Value-level
    // ------------------------------------------------------------------

    private String generateValueInternal(
            String conceptName,
            String valueName,
            String mappingType,
            String detectedType,
            List<String> sampleValues,
            String rangeInfo) {

        if (llmEnabled) {
            try {
                String prompt = buildValuePrompt(
                        conceptName, valueName, mappingType, detectedType, sampleValues);
                String result = callLlm(prompt);
                if (result != null && !result.isEmpty()) {
                    // Append range annotation when available so factual data is preserved.
                    if (rangeInfo != null && !rangeInfo.isEmpty()) {
                        return result + " [" + rangeInfo + "]";
                    }
                    return result;
                }
            } catch (Exception e) {
                logger.warn("LLM value description failed for '{}/{}': {}",
                        conceptName, valueName, e.getMessage());
            }
        }
        return templateValueDescription(
                conceptName, valueName, mappingType, detectedType, rangeInfo);
    }

    private String buildValuePrompt(
            String conceptName,
            String valueName,
            String mappingType,
            String detectedType,
            List<String> sampleValues) {

        StringBuilder sb = new StringBuilder();
        sb.append("Describe this harmonized value in a clinical context, 1-2 sentences:\n");
        sb.append("Data element: ").append(conceptName).append("\n");
        sb.append("Value: ").append(valueName).append("\n");
        sb.append("Data type: ").append(detectedType).append("\n");
        sb.append("Harmonization: ").append(mappingType).append("\n");
        if (sampleValues != null && !sampleValues.isEmpty()) {
            List<String> limited = limit(sampleValues, MAX_SAMPLE_VALUES);
            sb.append("Source values: ").append(String.join(", ", limited)).append("\n");
        }
        sb.append("Provide a concise clinical description only.");
        return sb.toString();
    }

    private String templateValueDescription(
            String conceptName,
            String valueName,
            String mappingType,
            String detectedType,
            String rangeInfo) {

        String h = StringUtil.humanize(conceptName);
        String v = (valueName != null && !valueName.isEmpty()) ? valueName : "";

        String base;
        switch (mappingType) {
            case "ordinal":
                base = "Ordinal " + h + " value " + v
                        + " mapped to a canonical interval in the harmonized scale.";
                break;
            case "range":
                if ("date".equalsIgnoreCase(detectedType)) {
                    base = StringUtil.capitalize(h) + " date range harmonized across source datasets.";
                } else {
                    base = StringUtil.capitalize(h) + " numeric range harmonized across source datasets.";
                }
                break;
            case "categorical":
                if (!v.isEmpty()) {
                    base = "'" + v + "': " + h
                            + " category harmonized from equivalent representations across datasets.";
                } else {
                    base = StringUtil.capitalize(h) + " category harmonized across datasets.";
                }
                break;
            case "enum":
                if (!v.isEmpty()) {
                    base = "'" + v + "': standardized " + h
                            + " value matched from source data by semantic similarity.";
                } else {
                    base = "Standardized " + h
                            + " value matched from source data by semantic similarity.";
                }
                break;
            case "clustered":
                if (!v.isEmpty()) {
                    base = "'" + v + "': " + h
                            + " cluster grouping semantically similar values across datasets.";
                } else {
                    base = StringUtil.capitalize(h)
                            + " cluster grouping semantically similar values across datasets.";
                }
                break;
            default:
                base = StringUtil.capitalize(h) + " value '" + v + "' harmonized across source datasets.";
                break;
        }

        if (rangeInfo != null && !rangeInfo.isEmpty()) {
            return base + " [" + rangeInfo + "]";
        }
        return base;
    }

    // ------------------------------------------------------------------
    // LLM HTTP call – OpenAI-compatible format
    // Works with Ollama (/v1/chat/completions), OpenAI, LM Studio, etc.
    // ------------------------------------------------------------------

    private String callLlm(String userMessage) {
        try {
            RestTemplate restTemplate = restTemplateConfig.getRestTemplate();

            ObjectNode request = objectMapper.createObjectNode();
            request.put("model", llmModel);
            request.put("temperature", 0.3);
            request.put("max_tokens", MAX_TOKENS);
            request.put("stream", false);

            ArrayNode messages = objectMapper.createArrayNode();

            ObjectNode systemMsg = objectMapper.createObjectNode();
            systemMsg.put("role", "system");
            systemMsg.put("content", SYSTEM_PROMPT);
            messages.add(systemMsg);

            ObjectNode userMsg = objectMapper.createObjectNode();
            userMsg.put("role", "user");
            userMsg.put("content", userMessage);
            messages.add(userMsg);

            request.set("messages", messages);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (llmApiKey != null && !llmApiKey.trim().isEmpty()) {
                headers.setBearerAuth(llmApiKey.trim());
            }

            String url = llmBaseUrl.replaceAll("/+$", "") + "/v1/chat/completions";
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(request), headers);

            ResponseEntity<String> response =
                    restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode content = root.path("choices").path(0).path("message").path("content");
                if (!content.isMissingNode()) {
                    return content.asText("").trim();
                }
            }
        } catch (Exception e) {
            logger.warn("LLM API call to '{}' (model: {}) failed: {}", llmBaseUrl, llmModel, e.getMessage());
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Utilities
    // ------------------------------------------------------------------

    private static String safe(String s) {
        return StringUtil.safe(s);
    }

    private static List<String> limit(List<String> list, int max) {
        if (list == null) return new ArrayList<>();
        return list.size() > max ? list.subList(0, max) : list;
    }
}
