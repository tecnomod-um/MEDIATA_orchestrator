package org.taniwha.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JsonSchemaParsingUtilTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parseSchemaProperties_handlesNullBlankInvalidAndNestedSchema() {
        assertThat(JsonSchemaParsingUtil.parseSchemaProperties(null, "{}", 3)).isEmpty();
        assertThat(JsonSchemaParsingUtil.parseSchemaProperties(objectMapper, null, 3)).isEmpty();
        assertThat(JsonSchemaParsingUtil.parseSchemaProperties(objectMapper, "   ", 3)).isEmpty();
        assertThat(JsonSchemaParsingUtil.parseSchemaProperties(objectMapper, "null", 3)).isEmpty();
        assertThat(JsonSchemaParsingUtil.parseSchemaProperties(objectMapper, "{not json}", 3)).isEmpty();

        String wrapped = """
                {
                  "schema": "{\\"properties\\":{\\"age\\":{\\"type\\":\\"integer\\",\\"enum\\":[\\" 1 \\", null, \\"2\\"]}}}"
                }
                """;

        List<JsonSchemaParsingUtil.SchemaFieldDef> defs =
                JsonSchemaParsingUtil.parseSchemaProperties(objectMapper, wrapped, 2);

        assertThat(defs).hasSize(1);
        assertThat(defs.get(0).getName()).isEqualTo("age");
        assertThat(defs.get(0).getType()).isEqualTo("integer");
        assertThat(defs.get(0).getEnumValues()).containsExactly("1", "2");
    }

    @Test
    void parseSchemaProperties_extractsArrayUnionAndNestedItemTypes() {
        String schema = """
                {
                  "properties": {
                    "tags": {"type":"array","items":{"type":"string"}},
                    "choices": {"anyOf":[{"type":"null"},{"type":"number"}]},
                    "matrix": {"type":"array","items":{"items":{"type":"integer"}}},
                    "fallback": {"oneOf":[{"type":"string"},{"type":"boolean"}]}
                  }
                }
                """;

        List<JsonSchemaParsingUtil.SchemaFieldDef> defs =
                JsonSchemaParsingUtil.parseSchemaProperties(objectMapper, schema, 5);

        assertThat(defs).extracting(JsonSchemaParsingUtil.SchemaFieldDef::getType)
                .containsExactly("array<string>", "number", "array<integer>", "string|boolean");
    }

    @Test
    void unwrapJsonStringNode_unwrapsTextualJsonAndStopsOnPlainText() throws Exception {
        JsonNode nestedJson = objectMapper.readTree("""
                "\\"{\\\\\\"properties\\\\\\":{\\\\\\"name\\\\\\":{\\\\\\"type\\\\\\":\\\\\\"string\\\\\\"}}}\\""
                """);
        JsonNode unwrapped = JsonSchemaParsingUtil.unwrapJsonStringNode(objectMapper, nestedJson, 3);

        assertThat(unwrapped).isNotNull();
        assertThat(unwrapped.isObject()).isTrue();
        assertThat(unwrapped.get("properties").get("name").get("type").asText()).isEqualTo("string");

        JsonNode plainText = objectMapper.readTree("\"plain-text\"");
        JsonNode unchanged = JsonSchemaParsingUtil.unwrapJsonStringNode(objectMapper, plainText, 3);
        assertThat(unchanged).isSameAs(plainText);
    }

    @Test
    void extractTypeHelpers_coverUnionAndArrayVariants() throws Exception {
        JsonNode arrayDef = objectMapper.readTree("{\"type\":[\"null\",\"integer\"]}");
        JsonNode unionDef = objectMapper.readTree("[{\"type\":\"null\"},{\"type\":\"string\"},{\"type\":\"boolean\"}]");
        JsonNode emptyUnion = objectMapper.readTree("[{}]");

        assertThat(JsonSchemaParsingUtil.readTypeNode(arrayDef.get("type"))).isEqualTo("integer");
        assertThat(JsonSchemaParsingUtil.scanUnionTypes(unionDef)).isEqualTo("null|string|boolean");
        assertThat(JsonSchemaParsingUtil.scanUnionTypes(emptyUnion)).isEmpty();
        assertThat(JsonSchemaParsingUtil.extractTypeExtended(objectMapper.readTree("{\"type\":\"array\"}")))
                .isEqualTo("array");
    }

    @Test
    void extractEnum_respectsCapAndSkipsBlankValues() throws Exception {
        JsonNode def = objectMapper.readTree("""
                {"enum":[" A ", "", null, "B", "C"]}
                """);

        assertThat(JsonSchemaParsingUtil.extractEnum(def, 2)).containsExactly("A", "B");
        assertThat(JsonSchemaParsingUtil.extractEnum(def, 0)).isEmpty();
        assertThat(JsonSchemaParsingUtil.extractEnum(null, 3)).isEmpty();
    }
}
