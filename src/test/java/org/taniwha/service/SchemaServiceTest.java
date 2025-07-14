package org.taniwha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaServiceTest {

    private SchemaService svc;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        svc = new SchemaService(mapper);
    }

    @Test
    void saveAndGetAndRemoveSchema() throws Exception {
        assertThat(svc.getSchema()).isNull();
        Person p = new Person("Alice", 30);
        svc.saveSchema(p);
        String json = svc.getSchema();
        assertThat(json).contains("\"name\":\"Alice\"").contains("\"age\":30");
        svc.saveSchema(new java.util.HashMap<String, Object>() {{
            put("foo", "bar");
        }});
        assertThat(svc.getSchema()).isEqualTo("{\"foo\":\"bar\"}");

        svc.removeSchema();
        assertThat(svc.getSchema()).isNull();
    }

    public static class Person {
        public String name;
        public int age;

        public Person(String n, int a) {
            name = n;
            age = a;
        }
    }
}
