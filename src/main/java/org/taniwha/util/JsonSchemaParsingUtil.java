package org.taniwha.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

public final class JsonSchemaParsingUtil {
    private JsonSchemaParsingUtil() {}

    public static final class SchemaFieldDef {
        private final String name;
        private final String type;
        private final List<String> enumValues;

        public SchemaFieldDef(String name, String type, List<String> enumValues) {
            this.name = name == null ? "" : name;
            this.type = type == null ? "" : type;
            this.enumValues = enumValues == null ? Collections.emptyList() : enumValues;
        }

        public String getName() { return name; }
        public String getType() { return type; }
        public List<String> getEnumValues() { return enumValues; }
    }

    public static List<SchemaFieldDef> parseSchemaProperties(ObjectMapper objectMapper, String schemaJson, int maxEnum) {
        if (objectMapper == null) return Collections.emptyList();
        if (schemaJson == null) return Collections.emptyList();

        String trimmed = schemaJson.trim();
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed)) return Collections.emptyList();

        try {
            JsonNode root = objectMapper.readTree(trimmed);
            root = unwrapJsonStringNode(objectMapper, root, 3);

            if (root == null || !root.isObject()) return Collections.emptyList();

            if (root.has("schema")) {
                JsonNode maybe = unwrapJsonStringNode(objectMapper, root.get("schema"), 3);
                if (maybe != null && maybe.isObject()) root = maybe;
            }

            JsonNode props = root.get("properties");
            if (props == null || !props.isObject()) return Collections.emptyList();

            List<SchemaFieldDef> out = new ArrayList<>();
            Iterator<String> it = props.fieldNames();
            while (it.hasNext()) {
                String fieldName = it.next();
                JsonNode def = props.get(fieldName);

                String type = extractTypeExtended(def);
                List<String> enumVals = extractEnum(def, maxEnum);

                out.add(new SchemaFieldDef(fieldName, type, enumVals));
            }

            return out;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public static JsonNode unwrapJsonStringNode(ObjectMapper objectMapper, JsonNode node, int maxDepth) {
        JsonNode cur = node;
        for (int i = 0; i < maxDepth; i++) {
            if (cur == null) return null;
            if (!cur.isTextual()) return cur;

            String txt = cur.asText("");
            if (txt == null) return cur;
            String t = txt.trim();
            if (t.isEmpty() || "null".equalsIgnoreCase(t)) return null;

            if (!(t.startsWith("{") || t.startsWith("[") || t.startsWith("\"{") || t.startsWith("\"["))) {
                return cur;
            }

            try {
                cur = objectMapper.readTree(t);
            } catch (Exception ex) {
                try {
                    String unquoted = t;
                    if (unquoted.startsWith("\"") && unquoted.endsWith("\"") && unquoted.length() >= 2) {
                        unquoted = unquoted.substring(1, unquoted.length() - 1);
                        unquoted = unquoted.replace("\\\"", "\"").replace("\\\\", "\\");
                        cur = objectMapper.readTree(unquoted);
                    } else {
                        return cur;
                    }
                } catch (Exception ex2) {
                    return cur;
                }
            }
        }
        return cur;
    }

    public static String extractTypeExtended(JsonNode def) {
        if (def == null || def.isNull()) return "";

        JsonNode t = def.get("type");
        String base = readTypeNode(t);

        if ("array".equalsIgnoreCase(base)) {
            JsonNode items = def.get("items");
            String itemType = "";
            if (items != null && !items.isNull()) {
                itemType = readTypeNode(items.get("type"));
                if (itemType.isEmpty() && items.has("items")) {
                    itemType = readTypeNode(items.get("items").get("type"));
                }
            }
            if (!itemType.isEmpty()) return "array<" + itemType.toLowerCase(Locale.ROOT) + ">";
            return "array";
        }

        if (base.isEmpty()) {
            JsonNode anyOf = def.get("anyOf");
            if (anyOf != null && anyOf.isArray()) {
                String union = scanUnionTypes(anyOf);
                if (!union.isEmpty()) return union;
            }
            JsonNode oneOf = def.get("oneOf");
            if (oneOf != null && oneOf.isArray()) {
                String union = scanUnionTypes(oneOf);
                if (!union.isEmpty()) return union;
            }
        }

        return base;
    }

    public static String scanUnionTypes(JsonNode arr) {
        Set<String> types = new LinkedHashSet<>();
        for (int i = 0; i < arr.size(); i++) {
            JsonNode n = arr.get(i);
            if (n == null || n.isNull()) continue;
            String t = readTypeNode(n.get("type"));
            if (!t.isEmpty()) types.add(t.toLowerCase(Locale.ROOT));
        }
        if (types.isEmpty()) return "";
        if (types.size() == 1) return types.iterator().next();
        if (types.size() == 2 && types.contains("null")) {
            for (String x : types) if (!"null".equals(x)) return x;
        }
        return String.join("|", types);
    }

    public static String readTypeNode(JsonNode t) {
        if (t == null || t.isNull()) return "";
        if (t.isTextual()) return t.asText("");
        if (t.isArray() && t.size() > 0) {
            for (int i = 0; i < t.size(); i++) {
                JsonNode x = t.get(i);
                if (x != null && x.isTextual()) {
                    String v = x.asText("");
                    if (!v.isEmpty() && !"null".equalsIgnoreCase(v)) return v;
                }
            }
            JsonNode first = t.get(0);
            if (first != null && first.isTextual()) return first.asText("");
        }
        return "";
    }

    public static List<String> extractEnum(JsonNode def, int maxEnum) {
        if (def == null) return Collections.emptyList();
        JsonNode en = def.get("enum");
        if (en == null || !en.isArray()) return Collections.emptyList();

        List<String> out = new ArrayList<>();
        int cap = Math.max(0, maxEnum);
        for (int i = 0; i < en.size() && out.size() < cap; i++) {
            JsonNode v = en.get(i);
            if (v == null || v.isNull()) continue;
            String s = v.asText(null);
            if (s == null) continue;
            s = s.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }
}
