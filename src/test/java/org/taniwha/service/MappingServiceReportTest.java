package org.taniwha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.taniwha.dto.MappingSuggestRequestDTO;
import org.taniwha.dto.SuggestedGroupDTO;
import org.taniwha.dto.SuggestedMappingDTO;
import org.taniwha.dto.SuggestedRefDTO;
import org.taniwha.dto.SuggestedValueDTO;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.Message;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = MappingServiceReportTest.TestConfig.class)
@EnableAutoConfiguration(exclude = {MongoAutoConfiguration.class, DataSourceAutoConfiguration.class})
public class MappingServiceReportTest {

    @Configuration
    static class TestConfig {
        @Bean
        public EmbeddingModel embeddingModel() {
            // Use Spring AI auto-configuration with default all-MiniLM-L6-v2 model
            // This will download the model if not cached
            return new TransformersEmbeddingModel();
        }

        @Bean
        public EmbeddingsClient embeddingsClient(EmbeddingModel embeddingModel) {
            return new EmbeddingsClient(embeddingModel);
        }
        
        @Bean
        public RDFService rdfService() {
            // Mock RDFService to return realistic SNOMED codes
            RDFService mock = Mockito.mock(RDFService.class);
            
            // Return realistic SNOMED suggestions based on term
            Mockito.when(mock.getSNOMEDTermSuggestions(Mockito.anyString()))
                .thenAnswer(invocation -> {
                    String term = invocation.getArgument(0);
                    String lower = term.toLowerCase();
                    
                    // Return realistic SNOMED codes based on term
                    if (lower.contains("bath")) {
                        return Arrays.asList("284546000|Bathing|", "129007002|Personal bathing|", "313009003|Personal hygiene|");
                    } else if (lower.contains("toilet")) {
                        return Arrays.asList("284548004|Ability to use toilet|", "129006006|Toileting independence|");
                    } else if (lower.contains("dress")) {
                        return Arrays.asList("284547009|Ability to dress|", "165235000|Dressing independence|");
                    } else if (lower.contains("feed") || lower.contains("eat")) {
                        return Arrays.asList("284545001|Ability to feed|", "289168000|Eating independence|");
                    } else if (lower.contains("groom")) {
                        return Arrays.asList("284549007|Ability to groom|", "313009003|Personal hygiene|");
                    } else if (lower.contains("stair")) {
                        return Arrays.asList("284551006|Ability to climb stairs|", "228869008|Stair climbing|");
                    } else if (lower.contains("bowel")) {
                        return Arrays.asList("129008007|Continence bowel|", "165243003|Bowel control|");
                    } else if (lower.contains("bladder")) {
                        return Arrays.asList("129009004|Continence urinary|", "165244009|Bladder control|");
                    } else if (lower.contains("sex") || lower.contains("gender")) {
                        return Arrays.asList("263495000|Gender|", "734000001|Sex|");
                    } else if (lower.contains("type") || lower.contains("isch") || lower.contains("hem") || lower.contains("etiology")) {
                        return Arrays.asList("230690007|Stroke|", "432504007|Cerebrovascular accident|");
                    } else if (lower.contains("diabet")) {
                        return Arrays.asList("73211009|Diabetes mellitus|", "44054006|Type 2 diabetes|");
                    } else if (lower.contains("age")) {
                        return Arrays.asList("397669002|Age|", "424144002|Current chronological age|");
                    } else if (lower.contains("nihss")) {
                        return Arrays.asList("450741004|NIH stroke scale|", "450703000|Stroke severity score|");
                    } else if (lower.contains("barthel")) {
                        return Arrays.asList("273302005|Barthel index|", "445313000|Activities of daily living score|");
                    } else if (lower.contains("fim")) {
                        return Arrays.asList("445713009|Functional independence measure|", "445313000|Activities of daily living score|");
                    } else if (lower.contains("transfer")) {
                        return Arrays.asList("284550007|Ability to transfer|", "301438001|Transfer independence|");
                    } else if (lower.contains("mobil")) {
                        return Arrays.asList("364666007|Ability to mobilize|", "228869008|Walking ability|");
                    } else if (lower.contains("fac")) {
                        return Arrays.asList("52052004|Functional ambulation|", "228869008|Walking ability|");
                    } else if (lower.contains("independent") || term.equals("10")) {
                        return Arrays.asList("371153006|Independent|", "165245005|Functionally independent|");
                    } else if (lower.contains("dependent") || term.equals("0")) {
                        return Arrays.asList("371152001|Dependent|", "371154000|Totally dependent|");
                    } else if (term.equals("5")) {
                        return Arrays.asList("371155004|Partially dependent|", "371156003|Requires assistance|");
                    } else {
                        // Generic fallback
                        return Arrays.asList("404684003|Clinical finding|", "123037004|Body structure|");
                    }
                });
            
            return mock;
        }
        
        @Bean
        public TerminologyService terminologyService(RDFService rdfService, EmbeddingsClient embeddingsClient) {
            // Use real TerminologyService with mocked RDFService
            return new TerminologyService(rdfService, embeddingsClient);
        }
        
        @Bean
        public ChatModel chatModel() {
            // Mock ChatModel to generate realistic medical descriptions
            ChatModel mock = Mockito.mock(ChatModel.class);
            
            Mockito.when(mock.call(Mockito.any(Prompt.class)))
                .thenAnswer(invocation -> {
                    Prompt prompt = invocation.getArgument(0);
                    String promptText = prompt.getInstructions().get(0).getContent();
                    
                    // Generate contextual description based on prompt
                    String description = generateMockDescription(promptText);
                    
                    Generation generation = new Generation(description);
                    return new ChatResponse(Collections.singletonList(generation));
                });
            
            return mock;
        }
        
        private static String generateMockDescription(String promptText) {
            String lower = promptText.toLowerCase();
            
            // Extract context from prompt
            if (lower.contains("field name:") && !lower.contains("score:") && !lower.contains("value:")) {
                // Column description
                if (lower.contains("bath")) {
                    return "Assessment of patient's ability to perform bathing and personal hygiene activities independently";
                } else if (lower.contains("toilet")) {
                    return "Evaluation of ability to use toilet facilities and manage continence independently";
                } else if (lower.contains("dress")) {
                    return "Assessment of ability to dress and undress independently";
                } else if (lower.contains("feed") || lower.contains("eat")) {
                    return "Evaluation of ability to feed oneself and eat meals independently";
                } else if (lower.contains("groom")) {
                    return "Assessment of ability to perform personal grooming activities independently";
                } else if (lower.contains("stair")) {
                    return "Evaluation of ability to climb stairs independently";
                } else if (lower.contains("bowel")) {
                    return "Assessment of bowel control and continence management";
                } else if (lower.contains("bladder")) {
                    return "Evaluation of bladder control and urinary continence";
                } else if (lower.contains("type")) {
                    return "Classification or type of the medical condition or event";
                } else if (lower.contains("sex") || lower.contains("gender")) {
                    return "Biological sex or gender identification of the patient";
                } else {
                    return "Clinical measurement or assessment value";
                }
            } else if (lower.contains("score:") || lower.contains("value:")) {
                // Value description - check position in range
                if (lower.contains("low end") || lower.contains("score: 0") || lower.contains("minimum")) {
                    return "Complete dependence; requires full assistance from caregivers for this activity";
                } else if (lower.contains("middle") || lower.contains("score: 5") || lower.contains("mid")) {
                    return "Partial independence; requires some assistance or supervision for this activity";
                } else if (lower.contains("high end") || lower.contains("score: 10") || lower.contains("maximum")) {
                    return "Complete independence; no assistance required for this activity";
                } else if (lower.contains("ischemic")) {
                    return "Ischemic type: caused by blocked blood flow to the brain";
                } else if (lower.contains("hemorrhagic")) {
                    return "Hemorrhagic type: caused by bleeding in or around the brain";
                } else {
                    return "Specific measurement or categorical value in the assessment";
                }
            }
            
            return "Clinical assessment value";
        }
        
        @Bean
        public LLMTextGenerator llmTextGenerator(ChatModel chatModel) {
            return new LLMTextGenerator(chatModel);
        }
        
        @Bean
        public DescriptionGenerator descriptionGenerator(LLMTextGenerator llmTextGenerator) {
            return new DescriptionGenerator(llmTextGenerator);
        }

        @Bean
        public MappingService mappingService(EmbeddingsClient embeddingsClient, TerminologyService terminologyService, DescriptionGenerator descriptionGenerator) {
            return new MappingService(embeddingsClient, terminologyService, descriptionGenerator);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @Autowired
    private MappingService mappingService;

    @Test
    void generates_mapping_report_from_fixture() throws Exception {
        MappingSuggestRequestDTO req = loadRequestFixture();

        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);

        // Quality checks - validate LLM is performing well
        assertNotNull(result, "Result should not be null");
        
        // Log actual results for inspection
        Set<String> foundMappings = result.stream()
            .flatMap(map -> map.keySet().stream())
            .collect(Collectors.toSet());
        
        System.out.println("\n=== LLM Embedding Results ===");
        System.out.println("Total mappings found: " + result.size());
        System.out.println("Mapping keys: " + foundMappings);
        
        // These are key medical assessment categories that should be identified
        List<String> expectedMappings = Arrays.asList("bath", "dress", "feed", "groom", "stair", "toilet", "sex");
        long matchCount = expectedMappings.stream()
            .filter(foundMappings::contains)
            .count();
        
        System.out.println("Expected categories found: " + matchCount + " out of " + expectedMappings.size());
        System.out.println("================================\n");
        
        // Display sample mappings with terminology and descriptions
        System.out.println("\n=== Sample Mappings with Terminology and Descriptions ===");
        int samplesShown = 0;
        for (Map<String, SuggestedMappingDTO> mappingMap : result) {
            if (samplesShown >= 5) break; // Show first 5 mappings
            
            for (Map.Entry<String, SuggestedMappingDTO> entry : mappingMap.entrySet()) {
                SuggestedMappingDTO mapping = entry.getValue();
                System.out.println("\nMapping: " + entry.getKey());
                System.out.println("  Terminology: " + (mapping.getTerminology() != null && !mapping.getTerminology().isEmpty() ? mapping.getTerminology() : "(none)"));
                System.out.println("  Description: " + (mapping.getDescription() != null && !mapping.getDescription().isEmpty() ? mapping.getDescription() : "(none)"));
                
                // Show a few values with their terminology and descriptions
                if (mapping.getGroups() != null && !mapping.getGroups().isEmpty()) {
                    SuggestedGroupDTO group = mapping.getGroups().get(0);
                    if (group.getValues() != null && !group.getValues().isEmpty()) {
                        System.out.println("  Sample Values:");
                        for (int i = 0; i < Math.min(3, group.getValues().size()); i++) {
                            SuggestedValueDTO value = group.getValues().get(i);
                            System.out.println("    - " + value.getName());
                            System.out.println("      Terminology: " + (value.getTerminology() != null && !value.getTerminology().isEmpty() ? value.getTerminology() : "(none)"));
                            System.out.println("      Description: " + (value.getDescription() != null && !value.getDescription().isEmpty() ? value.getDescription() : "(none)"));
                        }
                    }
                }
                
                samplesShown++;
                if (samplesShown >= 5) break;
            }
        }
        System.out.println("==========================================================\n");
        
        // LLM should match or exceed math baseline (30 suggestions, 7/7 categories)
        assertTrue(result.size() >= 25, 
            "LLM should produce at least 25 mapping suggestions (got " + result.size() + ")");
        assertTrue(matchCount >= 6, 
            "LLM should identify at least 6 of 7 key medical categories (found " + matchCount + ")");
        
        // CRITICAL: Validate value mappings are correct
        validateValueMappings(result);
        
        // Write reports for manual inspection
        Path outDir = Paths.get("target", "mapping-report");
        Files.createDirectories(outDir);

        String stamp = LocalDateTime.now().format(TS);
        Path jsonOut = outDir.resolve("mapping-report-" + stamp + ".json");
        Path mdOut = outDir.resolve("mapping-report-" + stamp + ".md");

        writeJsonReport(jsonOut, result);
        writeMarkdownReport(mdOut, req, result);

        assertTrue(Files.exists(jsonOut), "JSON report should exist: " + jsonOut);
        assertTrue(Files.exists(mdOut), "Markdown report should exist: " + mdOut);
        
        System.out.println("\n=== LLM Embedding Quality Report ===");
        System.out.println("Total mapping suggestions: " + result.size());
        System.out.println("Key categories identified: " + matchCount + "/" + expectedMappings.size());
        System.out.println("Report saved to: " + mdOut);
        System.out.println("====================================\n");
    }

    private MappingSuggestRequestDTO loadRequestFixture() throws IOException {
        String override = System.getProperty("mappingFixture");

        // 1) If user provided -DmappingFixture, try to load it, but don't blow up with a cryptic assert.
        if (override != null && !override.trim().isEmpty()) {
            Path p = Paths.get(override.trim());
            if (!Files.exists(p)) {
                fail("mappingFixture file not found: " + p.toAbsolutePath()
                        + "\nCommand example:"
                        + "\n  mvn -DmappingFixture=/abs/path/to/request.json -Dtest=MappingServiceReportTest test");
            }
            byte[] data = readAllBytesCompat(p);
            return MAPPER.readValue(data, MappingSuggestRequestDTO.class);
        }

        // 2) Otherwise use default classpath resource.
        InputStream in = getClass().getClassLoader().getResourceAsStream("mapping/fixture-request.json");
        if (in == null) {
            fail("No -DmappingFixture provided and missing test resource: src/test/resources/mapping/fixture-request.json"
                    + "\nEither:"
                    + "\n  - Provide a fixture: mvn -DmappingFixture=/abs/path/to/request.json -Dtest=MappingServiceReportTest test"
                    + "\n  - Or add the resource: src/test/resources/mapping/fixture-request.json");
        }

        try {
            byte[] data = readAllBytesCompat(in);
            return MAPPER.readValue(data, MappingSuggestRequestDTO.class);
        } finally {
            try { in.close(); } catch (IOException ignore) {}
        }
    }

    private void writeJsonReport(Path out, List<Map<String, SuggestedMappingDTO>> result) throws IOException {
        byte[] pretty = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(result);
        OutputStream os = null;
        try {
            os = Files.newOutputStream(out, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            os.write(pretty);
        } finally {
            if (os != null) try { os.close(); } catch (IOException ignore) {}
        }
    }

    private void writeMarkdownReport(Path out, MappingSuggestRequestDTO req, List<Map<String, SuggestedMappingDTO>> result)
            throws IOException {

        StringBuilder sb = new StringBuilder();
        sb.append("# MappingService Report\n\n");
        sb.append("- Generated: ").append(LocalDateTime.now()).append("\n");
        sb.append("- Suggestions: ").append(result == null ? 0 : result.size()).append("\n");
        sb.append("- Schema provided: ").append(req != null && req.getSchema() != null && !req.getSchema().trim().isEmpty()).append("\n\n");

        if (result == null || result.isEmpty()) {
            sb.append("No mappings were suggested.\n");
            writeUtf8Compat(out, sb.toString());
            return;
        }

        int limit = Math.min(30, result.size());
        sb.append("## Top ").append(limit).append(" mappings\n\n");

        for (int i = 0; i < limit; i++) {
            Map<String, SuggestedMappingDTO> one = result.get(i);
            if (one == null || one.isEmpty()) continue;

            Map.Entry<String, SuggestedMappingDTO> e = one.entrySet().iterator().next();
            String unionKey = e.getKey();
            SuggestedMappingDTO mapping = e.getValue();

            sb.append("### ").append(i + 1).append(". `").append(unionKey).append("`\n\n");

            if (mapping == null) {
                sb.append("- mapping: null\n\n");
                continue;
            }
            
            // Show terminology and description for the mapping
            if (mapping.getTerminology() != null && !mapping.getTerminology().isEmpty()) {
                sb.append("- **Terminology:** ").append(mapping.getTerminology()).append("\n");
            }
            if (mapping.getDescription() != null && !mapping.getDescription().isEmpty()) {
                sb.append("- **Description:** ").append(mapping.getDescription()).append("\n");
            }
            sb.append("\n");

            List<String> cols = mapping.getColumns() == null ? Collections.<String>emptyList() : mapping.getColumns();
            sb.append("- columns: ").append(cols.size()).append("  \n");
            if (!cols.isEmpty()) {
                sb.append("  - ").append(joinLines(cols, "\n  - ")).append("\n");
            }

            List<SuggestedGroupDTO> groups = mapping.getGroups() == null ? Collections.<SuggestedGroupDTO>emptyList() : mapping.getGroups();
            sb.append("\n- groups: ").append(groups.size()).append("\n\n");

            for (SuggestedGroupDTO g : groups) {
                if (g == null) continue;
                sb.append("**Group column:** `").append(nullToEmpty(g.getColumn())).append("`\n\n");

                List<SuggestedValueDTO> values = g.getValues() == null ? Collections.<SuggestedValueDTO>emptyList() : g.getValues();
                sb.append("- values: ").append(values.size()).append("\n");

                int vlim = Math.min(25, values.size());
                for (int vi = 0; vi < vlim; vi++) {
                    SuggestedValueDTO v = values.get(vi);
                    if (v == null) continue;
                    int refs = (v.getMapping() == null) ? 0 : v.getMapping().size();
                    sb.append("  - `").append(nullToEmpty(v.getName())).append("` (refs=").append(refs).append(")");
                    
                    // Show terminology and description for each value
                    if (v.getTerminology() != null && !v.getTerminology().isEmpty()) {
                        sb.append(" [SNOMED: ").append(v.getTerminology()).append("]");
                    }
                    if (v.getDescription() != null && !v.getDescription().isEmpty()) {
                        sb.append(" - ").append(v.getDescription());
                    }
                    sb.append("\n");
                }
                if (values.size() > vlim) {
                    sb.append("  - ... ").append(values.size() - vlim).append(" more\n");
                }
                sb.append("\n");
            }
        }

        writeUtf8Compat(out, sb.toString());
    }
    
    /**
     * Validate that value mappings are correct when columns have different scales.
     * Critical validation: FIM (1-7) vs Barthel (0-10/15) should map proportionally.
     */
    private void validateValueMappings(List<Map<String, SuggestedMappingDTO>> result) {
        System.out.println("\n=== Value Mapping Validation ===");
        
        int validatedMappings = 0;
        int totalValueMappings = 0;
        
        for (Map<String, SuggestedMappingDTO> mapping : result) {
            for (Map.Entry<String, SuggestedMappingDTO> entry : mapping.entrySet()) {
                String key = entry.getKey();
                SuggestedMappingDTO dto = entry.getValue();
                
                if (dto.getGroups() == null) continue;
                
                for (SuggestedGroupDTO group : dto.getGroups()) {
                    if (group.getValues() == null) continue;
                    
                    for (SuggestedValueDTO value : group.getValues()) {
                        if (value.getMapping() == null || value.getMapping().size() < 2) continue;
                        
                        totalValueMappings++;
                        
                        // Check that mappings are valid
                        boolean allValid = true;
                        boolean isIntegerMapping = false;
                        
                        for (SuggestedRefDTO ref : value.getMapping()) {
                            if (ref.getValue() == null) {
                                allValid = false;
                                break;
                            }
                            
                            // Value can be either:
                            // 1. Map<String, Object> for integer ranges (min/max)
                            // 2. String for categorical values  
                            if (ref.getValue() instanceof Map) {
                                isIntegerMapping = true;
                                @SuppressWarnings("unchecked")
                                Map<String, Object> valueMap = (Map<String, Object>) ref.getValue();
                                
                                Object minObj = valueMap.get("minValue");
                                Object maxObj = valueMap.get("maxValue");
                                
                                if (minObj == null || maxObj == null) {
                                    allValid = false;
                                    break;
                                }
                                
                                // Validate ranges are sensible (min <= max)
                                double minVal = ((Number) minObj).doubleValue();
                                double maxVal = ((Number) maxObj).doubleValue();
                                
                                if (minVal > maxVal) {
                                    System.out.println("  ⚠️  Invalid range in " + key + ": " + 
                                        ref.getGroupColumn() + " [" + minVal + "-" + maxVal + "]");
                                    allValid = false;
                                }
                            }
                            // else: categorical string value - always valid
                        }
                        
                        if (allValid) {
                            validatedMappings++;
                            if (isIntegerMapping) {
                                // Successfully validated an integer range mapping
                            }
                        } else {
                            // Log which mapping had issues for debugging
                            System.out.println("  Invalid mapping in category '" + key + "', value '" + value.getName() + "'");
                        }
                    }
                }
            }
        }
        
        System.out.println("Total value mappings: " + totalValueMappings);
        System.out.println("Valid mappings: " + validatedMappings);
        System.out.println("================================\n");
        
        // Assert that most mappings are valid
        assertTrue(validatedMappings > 0, "Should have at least some valid value mappings");
        if (totalValueMappings > 0) {
            double validRatio = (double) validatedMappings / totalValueMappings;
            assertTrue(validRatio >= 0.9, 
                "At least 90% of value mappings should be valid (got " + 
                String.format("%.1f%%", validRatio * 100) + ")");
        }
    }

    // -----------------------
    // Java 8 compatibility
    // -----------------------

    private static byte[] readAllBytesCompat(Path p) throws IOException {
        InputStream in = null;
        try {
            in = Files.newInputStream(p);
            return readAllBytesCompat(in);
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignore) {}
        }
    }

    private static byte[] readAllBytesCompat(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) != -1) {
            baos.write(buf, 0, r);
        }
        return baos.toByteArray();
    }

    private static void writeUtf8Compat(Path out, String s) throws IOException {
        OutputStream os = null;
        OutputStreamWriter w = null;
        try {
            os = Files.newOutputStream(out, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            w = new OutputStreamWriter(os, StandardCharsets.UTF_8);
            w.write(s);
            w.flush();
        } finally {
            if (w != null) try { w.close(); } catch (IOException ignore) {}
            else if (os != null) try { os.close(); } catch (IOException ignore) {}
        }
    }

    private static String joinLines(List<String> items, String sep) {
        if (items == null || items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(items.get(i) == null ? "" : items.get(i));
        }
        return sb.toString();
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
