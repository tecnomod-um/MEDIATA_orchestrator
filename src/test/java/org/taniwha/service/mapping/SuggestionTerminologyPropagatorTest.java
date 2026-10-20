package org.taniwha.service.mapping;

import org.junit.jupiter.api.Test;
import org.taniwha.dto.ColumnInFileDTO;
import org.taniwha.dto.SuggestedGroupDTO;
import org.taniwha.dto.SuggestedMappingDTO;
import org.taniwha.dto.SuggestedRefDTO;
import org.taniwha.dto.SuggestedValueDTO;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SuggestionTerminologyPropagatorTest {

    private final SuggestionTerminologyPropagator propagator = new SuggestionTerminologyPropagator();

    @Test
    void propagatesColumnAndValueTerminologyWhenTwoSourcesAgree() {
        ColumnInFileDTO first = source(
                "node-a", "a.csv", "StrokeType", "Cerebrovascular accident | 230690007",
                Map.of("I", "Cerebral infarction | 432504007")
        );
        ColumnInFileDTO second = source(
                "node-b", "b.csv", "Etiology", "cerebrovascular accident | 230690007",
                Map.of("Isch", "Cerebral infarction | 432504007")
        );
        SuggestedMappingDTO mapping = suggestion(
                ref("node-a", "a.csv", "StrokeType", "I"),
                ref("node-b", "b.csv", "Etiology", "Isch")
        );

        propagator.propagate(List.of(first, second), List.of(Map.of("Stroke_Type", mapping)));

        assertThat(mapping.getTerminology()).isEqualTo("Cerebrovascular accident | 230690007");
        assertThat(mapping.getGroups().get(0).getValues().get(0).getTerminology())
                .isEqualTo("Cerebral infarction | 432504007");
    }

    @Test
    void doesNotPropagateSingleSourceOrConflictingTerms() {
        ColumnInFileDTO first = source(
                "node-a", "a.csv", "StrokeType", "Stroke | 230690007",
                Map.of("I", "Cerebral infarction | 432504007")
        );
        ColumnInFileDTO second = source(
                "node-b", "b.csv", "Etiology", "Etiology | 106229004",
                Map.of("Isch", "Hemorrhage | 50960005")
        );
        SuggestedMappingDTO conflicting = suggestion(
                ref("node-a", "a.csv", "StrokeType", "I"),
                ref("node-b", "b.csv", "Etiology", "Isch")
        );
        SuggestedMappingDTO single = suggestion(ref("node-a", "a.csv", "StrokeType", "I"));

        propagator.propagate(
                List.of(first, second),
                List.of(Map.of("conflicting", conflicting), Map.of("single", single))
        );

        assertThat(conflicting.getTerminology()).isNull();
        assertThat(conflicting.getGroups().get(0).getValues().get(0).getTerminology()).isNull();
        assertThat(single.getTerminology()).isNull();
        assertThat(single.getGroups().get(0).getValues().get(0).getTerminology()).isNull();
    }

    @Test
    void doesNotCountRepeatedRefsFromOneSourceAsAgreement() {
        ColumnInFileDTO source = source(
                "node-a", "a.csv", "Status", "Clinical status | 106229004",
                Map.of("Y", "Present | 52101004")
        );
        SuggestedRefDTO repeated = ref("node-a", "a.csv", "Status", "Y");
        SuggestedMappingDTO mapping = suggestion(repeated, repeated);

        propagator.propagate(List.of(source), List.of(Map.of("Status", mapping)));

        assertThat(mapping.getTerminology()).isNull();
        assertThat(mapping.getGroups().get(0).getValues().get(0).getTerminology()).isNull();
    }

    @Test
    void keepsExistingSuggestionTerminology() {
        ColumnInFileDTO first = source("n1", "a.csv", "A", "Source term | 123456", Map.of());
        ColumnInFileDTO second = source("n2", "b.csv", "B", "Source term | 123456", Map.of());
        SuggestedMappingDTO mapping = suggestion(
                ref("n1", "a.csv", "A", "string"),
                ref("n2", "b.csv", "B", "string")
        );
        mapping.setTerminology("Existing term | 654321");

        propagator.propagate(List.of(first, second), List.of(Map.of("Field", mapping)));

        assertThat(mapping.getTerminology()).isEqualTo("Existing term | 654321");
    }

    @Test
    void matchesSnomedUriAndLabelCodeFormsAndKeepsExistingValueTerminology() {
        ColumnInFileDTO first = source(
                "n1", "a.csv", "Status", "http://snomed.info/id/404684003",
                Map.of("Y", "Present | 52101004")
        );
        ColumnInFileDTO second = source(
                "n2", "b.csv", "Finding", "Clinical finding | 404684003",
                Map.of("Yes", "http://snomed.info/id/52101004")
        );
        SuggestedMappingDTO mapping = suggestion(
                ref("n1", "a.csv", "Status", "Y"),
                ref("n2", "b.csv", "Finding", "Yes")
        );
        mapping.getGroups().get(0).getValues().get(0)
                .setTerminology("Reviewed present finding | 52101004");

        propagator.propagate(List.of(first, second), List.of(Map.of("Status", mapping)));

        assertThat(mapping.getTerminology()).isEqualTo("http://snomed.info/id/404684003");
        assertThat(mapping.getGroups().get(0).getValues().get(0).getTerminology())
                .isEqualTo("Reviewed present finding | 52101004");
    }

    @Test
    void omitsAValueAnnotationWhenOneSourceProvidesConflictingTerms() {
        ColumnInFileDTO conflicting = source(
                "n1", "a.csv", "Status", "", Map.of("Y", "Present | 52101004")
        );
        ColumnInFileDTO duplicate = source(
                "n1", "a.csv", "Status", "", Map.of(" y ", "Absent | 2667000")
        );
        ColumnInFileDTO agreeing = source(
                "n2", "b.csv", "Finding", "", Map.of("Yes", "Present | 52101004")
        );
        SuggestedMappingDTO mapping = suggestion(
                ref("n1", "a.csv", "Status", "Y"),
                ref("n2", "b.csv", "Finding", "Yes")
        );

        propagator.propagate(
                List.of(conflicting, duplicate, agreeing),
                List.of(Map.of("Status", mapping))
        );

        assertThat(mapping.getGroups().get(0).getValues().get(0).getTerminology()).isNull();
    }

    private ColumnInFileDTO source(
            String nodeId,
            String fileName,
            String column,
            String terminology,
            Map<String, String> valueTerminologies
    ) {
        ColumnInFileDTO dto = new ColumnInFileDTO();
        dto.setNodeId(nodeId);
        dto.setFileName(fileName);
        dto.setColumn(column);
        dto.setTerminology(terminology);
        dto.setValueTerminologies(new LinkedHashMap<>(valueTerminologies));
        return dto;
    }

    private SuggestedMappingDTO suggestion(SuggestedRefDTO... refs) {
        SuggestedValueDTO value = new SuggestedValueDTO();
        value.setName("target");
        value.setMapping(List.of(refs));

        SuggestedGroupDTO group = new SuggestedGroupDTO();
        group.setColumn("target");
        group.setValues(List.of(value));

        SuggestedMappingDTO mapping = new SuggestedMappingDTO();
        mapping.setGroups(List.of(group));
        return mapping;
    }

    private SuggestedRefDTO ref(String nodeId, String fileName, String column, String value) {
        SuggestedRefDTO ref = new SuggestedRefDTO();
        ref.setNodeId(nodeId);
        ref.setFileName(fileName);
        ref.setGroupColumn(column);
        ref.setValue(value);
        return ref;
    }
}
