package org.taniwha.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.taniwha.dto.ElementFileDTO;
import org.taniwha.dto.MappingSuggestRequestDTO;
import org.taniwha.dto.SuggestedGroupDTO;
import org.taniwha.dto.SuggestedMappingDTO;
import org.taniwha.dto.SuggestedRefDTO;
import org.taniwha.dto.SuggestedValueDTO;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused tests for embedding-based column matching correctness:
 *
 * <ol>
 *   <li>"Type" (values: Ischemic/Hemorrhagic) and "Etiology (Isch/Hem)" (values: Hem/HEM/Isch)
 *       must be joined into the same mapping group because their value domains overlap via
 *       abbreviation (isch→ischemic, hem→hemorrhagic).</li>
 *   <li>Binary Y/N values for the same column from two different files must produce TWO
 *       separate output values (one for Y, one for N), never merged into one.</li>
 *   <li>When the same normalized value appears more than once (e.g. "L" and "l" both
 *       normalise to "l"), it must be deduplicated into a single output value.</li>
 * </ol>
 */
public class MappingServiceTest {

    private MappingService svc;

    @BeforeEach
    void setUp() {
        svc = new MappingService();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static ElementFileDTO col(String nodeId, String fileName, String column, String... values) {
        ElementFileDTO dto = new ElementFileDTO();
        dto.setNodeId(nodeId);
        dto.setFileName(fileName);
        dto.setColumn(column);
        dto.setValues(Arrays.asList(values));
        return dto;
    }

    private static MappingSuggestRequestDTO req(ElementFileDTO... cols) {
        MappingSuggestRequestDTO r = new MappingSuggestRequestDTO();
        r.setElementFiles(Arrays.asList(cols));
        return r;
    }

    /** Collect all (fileName, column) pairs referenced anywhere in the result. */
    private static Set<String> allColumnKeys(List<Map<String, SuggestedMappingDTO>> result) {
        Set<String> keys = new HashSet<>();
        if (result == null) return keys;
        for (Map<String, SuggestedMappingDTO> entry : result) {
            for (SuggestedMappingDTO m : entry.values()) {
                if (m == null || m.getGroups() == null) continue;
                for (SuggestedGroupDTO g : m.getGroups()) {
                    if (g == null || g.getValues() == null) continue;
                    for (SuggestedValueDTO v : g.getValues()) {
                        if (v == null || v.getMapping() == null) continue;
                        for (SuggestedRefDTO ref : v.getMapping()) {
                            if (ref != null) {
                                keys.add(ref.getFileName() + "::" + ref.getGroupColumn());
                            }
                        }
                    }
                }
            }
        }
        return keys;
    }

    /** Find the single mapping group that contains refs to both given (fileName, column) pairs. */
    private static SuggestedMappingDTO findGroupContainingBoth(
            List<Map<String, SuggestedMappingDTO>> result,
            String fileNameA, String columnA,
            String fileNameB, String columnB) {

        if (result == null) return null;
        for (Map<String, SuggestedMappingDTO> entry : result) {
            for (SuggestedMappingDTO m : entry.values()) {
                if (m == null || m.getGroups() == null) continue;
                boolean hasA = false, hasB = false;
                for (SuggestedGroupDTO g : m.getGroups()) {
                    if (g == null || g.getValues() == null) continue;
                    for (SuggestedValueDTO v : g.getValues()) {
                        if (v == null || v.getMapping() == null) continue;
                        for (SuggestedRefDTO ref : v.getMapping()) {
                            if (ref == null) continue;
                            if (fileNameA.equals(ref.getFileName()) && columnA.equals(ref.getGroupColumn())) hasA = true;
                            if (fileNameB.equals(ref.getFileName()) && columnB.equals(ref.getGroupColumn())) hasB = true;
                        }
                    }
                }
                if (hasA && hasB) return m;
            }
        }
        return null;
    }

    /** Collect value names for the first group in a mapping. */
    private static List<String> valueNames(SuggestedMappingDTO mapping) {
        List<String> names = new ArrayList<>();
        if (mapping == null || mapping.getGroups() == null) return names;
        for (SuggestedGroupDTO g : mapping.getGroups()) {
            if (g == null || g.getValues() == null) continue;
            for (SuggestedValueDTO v : g.getValues()) {
                if (v != null && v.getName() != null) names.add(v.getName());
            }
        }
        return names;
    }

    // -----------------------------------------------------------------------
    // Test 1: "Type" and "Etiology (Isch/Hem)" must be joined together
    // -----------------------------------------------------------------------

    /**
     * "Type" (file A, values: Ischemic/Hemorrhagic) and "Etiology (Isch/Hem)"
     * (file B, values: Hem/HEM/Isch) represent the same medical concept.
     * They must appear in the same mapping group.
     */
    @Test
    void typeAndEtiologyAreJoinedIntoOneMappingGroup() {
        List<Map<String, SuggestedMappingDTO>> result = svc.suggestMappings(req(
                col("nodeA", "fimbarthel.csv", "Type",
                        "Ischemic", "Hemorrhagic"),
                col("nodeB", "SCUBA_1.csv", "Etiology (Isch/Hem)",
                        "Hem", "HEM", "Isch")
        ));

        SuggestedMappingDTO joint = findGroupContainingBoth(
                result,
                "fimbarthel.csv", "Type",
                "SCUBA_1.csv", "Etiology (Isch/Hem)");

        assertNotNull(joint,
                "Expected 'Type' and 'Etiology (Isch/Hem)' to be joined in one mapping group, "
                        + "but they were in separate groups.");
    }

    /**
     * When joined, the value groups must reflect both the Ischemic/Isch family
     * and the Hemorrhagic/Hem family — exactly 2 distinct output values.
     */
    @Test
    void typeAndEtiologyJoinProducesTwoValueGroups() {
        List<Map<String, SuggestedMappingDTO>> result = svc.suggestMappings(req(
                col("nodeA", "fimbarthel.csv", "Type",
                        "Ischemic", "Hemorrhagic"),
                col("nodeB", "SCUBA_1.csv", "Etiology (Isch/Hem)",
                        "Hem", "HEM", "Isch")
        ));

        SuggestedMappingDTO joint = findGroupContainingBoth(
                result,
                "fimbarthel.csv", "Type",
                "SCUBA_1.csv", "Etiology (Isch/Hem)");

        assertNotNull(joint, "Type and Etiology must be in the same mapping group");

        List<String> names = valueNames(joint);
        assertEquals(2, names.size(),
                "Expected exactly 2 value groups (ischemic family + hemorrhagic family), got: " + names);
    }

    /**
     * Cross-file refs: each value group must contain refs from BOTH source files.
     */
    @Test
    void typeAndEtiologyValueGroupsSpanBothFiles() {
        List<Map<String, SuggestedMappingDTO>> result = svc.suggestMappings(req(
                col("nodeA", "fimbarthel.csv", "Type",
                        "Ischemic", "Hemorrhagic"),
                col("nodeB", "SCUBA_1.csv", "Etiology (Isch/Hem)",
                        "Hem", "HEM", "Isch")
        ));

        SuggestedMappingDTO joint = findGroupContainingBoth(
                result,
                "fimbarthel.csv", "Type",
                "SCUBA_1.csv", "Etiology (Isch/Hem)");

        assertNotNull(joint, "Type and Etiology must be in the same mapping group");

        // Each of the 2 value groups must reference both files
        if (joint.getGroups() == null) return;
        for (SuggestedGroupDTO g : joint.getGroups()) {
            if (g == null || g.getValues() == null) continue;
            for (SuggestedValueDTO v : g.getValues()) {
                if (v == null || v.getMapping() == null) continue;
                Set<String> files = new HashSet<>();
                for (SuggestedRefDTO ref : v.getMapping()) {
                    if (ref != null) files.add(ref.getFileName());
                }
                assertTrue(files.size() >= 2,
                        "Value '" + v.getName() + "' should have refs from at least 2 files but got: " + files);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Test 2: Y and N must remain separate values (never merged)
    // -----------------------------------------------------------------------

    /**
     * When two files both contribute a "Diabetes (Y/N)" column with values [Y, N],
     * the output must contain exactly two distinct value entries: one for Y and one for N.
     * They must NOT be collapsed into a single value.
     */
    @Test
    void binaryYNValuesFromTwoFilesProduceTwoSeparateOutputValues() {
        List<Map<String, SuggestedMappingDTO>> result = svc.suggestMappings(req(
                col("nodeA", "file_A.csv", "Diabetes (Y/N)", "Y", "N"),
                col("nodeB", "file_B.csv", "Diabetes (Y/N)", "Y", "N")
        ));

        // Find the mapping that contains Diabetes columns
        SuggestedMappingDTO mapping = findGroupContainingBoth(
                result,
                "file_A.csv", "Diabetes (Y/N)",
                "file_B.csv", "Diabetes (Y/N)");

        assertNotNull(mapping,
                "Expected both Diabetes (Y/N) columns to appear in the same mapping group");

        List<String> names = valueNames(mapping);
        assertEquals(2, names.size(),
                "Y and N must produce exactly 2 separate output values, got: " + names);

        // Both 'y' and 'n' must be present (after normalization to lower-case)
        assertTrue(names.contains("y"), "Output values must include 'y', got: " + names);
        assertTrue(names.contains("n"), "Output values must include 'n', got: " + names);
    }

    /**
     * Single-file Y/N column must also produce two separate output values.
     */
    @Test
    void binaryYNValuesFromSingleFileProduceTwoSeparateOutputValues() {
        List<Map<String, SuggestedMappingDTO>> result = svc.suggestMappings(req(
                col("nodeA", "file_A.csv", "Hypertension (Y/N)", "Y", "N"),
                col("nodeA", "file_A.csv", "Diabetes (Y/N)", "Y", "N")
        ));

        assertFalse(result.isEmpty(), "Expected at least one mapping suggestion");

        // For each Y/N column in the output, there must be exactly 2 value entries
        for (Map<String, SuggestedMappingDTO> entry : result) {
            for (SuggestedMappingDTO m : entry.values()) {
                List<String> names = valueNames(m);
                if (names.contains("y") || names.contains("n")) {
                    assertEquals(2, names.size(),
                            "Y/N column must produce exactly 2 values, got: " + names);
                    assertTrue(names.contains("y"), "Must contain 'y', got: " + names);
                    assertTrue(names.contains("n"), "Must contain 'n', got: " + names);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Test 3: Duplicate normalized values must be deduplicated
    // -----------------------------------------------------------------------

    /**
     * "Affected side (body) (R/L)" has raw values ["R", "L", "l"].
     * "L" and "l" both normalise to "l", so the output must have exactly 2 values
     * (r and l), NOT 3.
     */
    @Test
    void duplicateNormalizedValuesAreMergedIntoOneOutputValue() {
        List<Map<String, SuggestedMappingDTO>> result = svc.suggestMappings(req(
                col("nodeA", "SCUBA_2.csv", "Affected side (body) (R/L)", "R", "L", "l")
        ));

        assertFalse(result.isEmpty(), "Expected at least one mapping suggestion");

        SuggestedMappingDTO mapping = result.get(0).values().iterator().next();
        List<String> names = valueNames(mapping);

        assertEquals(2, names.size(),
                "\"L\" and \"l\" must be merged into one value 'l', giving exactly 2 values (r, l). Got: " + names);
        assertTrue(names.contains("l"), "Must contain 'l', got: " + names);
        assertTrue(names.contains("r"), "Must contain 'r', got: " + names);
    }

    /**
     * When the same value appears twice because it is contributed from two files,
     * it should appear as one output value with refs from both files.
     */
    @Test
    void sameNormalizedValueFromTwoFilesIsDeduplicatedIntoOneEntry() {
        // Both files have "Type" with the same value "ischemic"
        List<Map<String, SuggestedMappingDTO>> result = svc.suggestMappings(req(
                col("nodeA", "file_A.csv", "Stroke Type", "Ischemic", "Hemorrhagic"),
                col("nodeB", "file_B.csv", "Stroke Type", "Ischemic", "Hemorrhagic")
        ));

        SuggestedMappingDTO mapping = findGroupContainingBoth(
                result,
                "file_A.csv", "Stroke Type",
                "file_B.csv", "Stroke Type");

        assertNotNull(mapping, "Both Stroke Type columns should be in the same group");

        List<String> names = valueNames(mapping);
        // Should be exactly 2 output values (ischemic and hemorrhagic), not 4
        assertEquals(2, names.size(),
                "Duplicate values from two files must be merged into one entry each. Got: " + names);
    }

    // -----------------------------------------------------------------------
    // Test 4: Full fixture regression — the original problem-statement scenarios
    // -----------------------------------------------------------------------

    /**
     * Using the actual fixture from the problem statement, verify that:
     * <ul>
     *   <li>"Type" and "Etiology (Isch/Hem)" appear in the same group.</li>
     *   <li>The Y/N columns (Hypertension, Diabetes, AF) each produce their own
     *       mappings with Y and N as separate values.</li>
     * </ul>
     */
    @Test
    void fixtureScenario_typeAndEtiologyJoined_andYNRemainSeparate() throws Exception {
        java.io.InputStream in = getClass().getClassLoader()
                .getResourceAsStream("mapping/fixture-request.json");
        if (in == null) return; // skip if resource not available

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        byte[] data;
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) baos.write(buf, 0, r);
            data = baos.toByteArray();
        } finally {
            try { in.close(); } catch (java.io.IOException ignore) {}
        }

        MappingSuggestRequestDTO req = mapper.readValue(data, MappingSuggestRequestDTO.class);
        List<Map<String, SuggestedMappingDTO>> result = svc.suggestMappings(req);

        // 1. Type + Etiology must be joined
        SuggestedMappingDTO joint = findGroupContainingBoth(
                result,
                "fimbartheltodos_elements.csv", "Type",
                "SCUBA_1_elements.csv", "Etiology (Isch/Hem)");

        assertNotNull(joint,
                "Fixture: 'Type' and 'Etiology (Isch/Hem)' must be in the same mapping group");

        // 2. Y/N columns must produce separate Y and N values
        for (Map<String, SuggestedMappingDTO> entry : result) {
            for (SuggestedMappingDTO m : entry.values()) {
                List<String> names = valueNames(m);
                if (names.contains("y") || names.contains("n")) {
                    // If a mapping has Y or N values, both must be present and no others
                    // (only relevant for pure Y/N binary columns like Hypertension, Diabetes)
                    boolean hasOnlyYN = names.stream()
                            .allMatch(v -> "y".equals(v) || "n".equals(v));
                    if (hasOnlyYN) {
                        assertEquals(2, names.size(),
                                "Y/N column must produce exactly 2 separate values (y, n), got: " + names);
                        assertTrue(names.contains("y"),
                                "Must contain 'y' but got: " + names);
                        assertTrue(names.contains("n"),
                                "Must contain 'n' but got: " + names);
                    }
                }
            }
        }
    }
}
