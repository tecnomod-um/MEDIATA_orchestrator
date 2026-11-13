package org.taniwha.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MappingSuggestRequestDTOTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deserializesSourceTerminologyWithoutChangingValues() throws Exception {
        String json = """
                {
                  "elementFiles": [
                    {
                      "nodeId": "node-a",
                      "fileName": "stroke.csv",
                      "column": "StrokeType",
                      "values": ["I", "H"],
                      "terminology": "Cerebrovascular accident | 230690007",
                      "valueTerminologies": {
                        "I": "Cerebral infarction | 432504007",
                        "H": "Intracranial hemorrhage | 274100004"
                      }
                    }
                  ]
                }
                """;

        MappingSuggestRequestDTO request = objectMapper.readValue(json, MappingSuggestRequestDTO.class);
        ColumnInFileDTO source = request.getElementFiles().get(0);

        assertThat(source.getValues()).containsExactly("I", "H");
        assertThat(source.getTerminology()).isEqualTo("Cerebrovascular accident | 230690007");
        assertThat(source.getValueTerminologies())
                .containsEntry("I", "Cerebral infarction | 432504007")
                .containsEntry("H", "Intracranial hemorrhage | 274100004");
    }

    @Test
    void remainsBackwardCompatibleWhenTerminologyIsAbsent() throws Exception {
        String json = """
                {
                  "elementFiles": [
                    {
                      "nodeId": "node-a",
                      "fileName": "stroke.csv",
                      "column": "StrokeType",
                      "values": ["I", "H"]
                    }
                  ]
                }
                """;

        MappingSuggestRequestDTO request = objectMapper.readValue(json, MappingSuggestRequestDTO.class);
        ColumnInFileDTO source = request.getElementFiles().get(0);

        assertThat(source.getTerminology()).isNull();
        assertThat(source.getValueTerminologies()).isNull();
    }
}
