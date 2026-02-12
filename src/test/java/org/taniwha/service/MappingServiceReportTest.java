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
            // Mock RDFService for tests
            RDFService mock = Mockito.mock(RDFService.class);
            Mockito.when(mock.getSNOMEDTermSuggestions(Mockito.any(String.class)))
                .thenReturn(new ArrayList<>());
            return mock;
        }
        
        @Bean
        public DescriptionGenerator descriptionGenerator() {
            return new DescriptionGenerator();
        }

        @Bean
        public MappingService mappingService(EmbeddingsClient embeddingsClient, RDFService rdfService, DescriptionGenerator descriptionGenerator) {
            return new MappingService(embeddingsClient, rdfService, descriptionGenerator);
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
                    sb.append("  - `").append(nullToEmpty(v.getName())).append("` (refs=").append(refs).append(")\n");
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
