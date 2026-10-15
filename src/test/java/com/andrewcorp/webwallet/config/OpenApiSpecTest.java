package com.andrewcorp.webwallet.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps {@code api/openapi.json} in sync with the code. The web client generates its API client from this committed
 * spec, so a change of the API must update it in the same commit.
 */
class OpenApiSpecTest extends AbstractIntegrationTest {

    private static final Path COMMITTED_SPEC = Path.of("api", "openapi.json");
    private static final Path GENERATED_SPEC = Path.of("target", "openapi.json");
    private static final String UPDATE_PROPERTY = "openapi.update";

    /** Sorted keys and LF line endings, so that the file does not depend on the platform or the bean order. */
    private static final JsonMapper PRINTER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    @Test
    void committedSpecIsUpToDate() throws Exception {
        String spec = normalize(mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        if (Boolean.getBoolean(UPDATE_PROPERTY)) {
            Files.createDirectories(COMMITTED_SPEC.getParent());
            Files.writeString(COMMITTED_SPEC, spec);
            return;
        }
        Files.createDirectories(GENERATED_SPEC.getParent());
        Files.writeString(GENERATED_SPEC, spec);
        String committed =
                Files.exists(COMMITTED_SPEC) ? Files.readString(COMMITTED_SPEC).replace("\r\n", "\n") : "";
        assertThat(spec)
                .as(
                        "%s is out of date; the current spec is written to %s. Regenerate it with"
                                + " `mvn test -Dtest=OpenApiSpecTest -D%s=true` and commit it",
                        COMMITTED_SPEC, GENERATED_SPEC, UPDATE_PROPERTY)
                .isEqualTo(committed);
    }

    @Test
    @SuppressWarnings("unchecked")
    void typesAreExactForGeneratedClients() throws Exception {
        Map<String, Object> spec = json(get("/v3/api-docs"), 200);
        Map<String, Object> schemas =
                (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");

        // Responses: every field is present, nullable ones may be null
        Map<String, Object> transaction = (Map<String, Object>) schemas.get("TransactionResponse");
        assertThat((List<String>) transaction.get("required")).contains("id", "amount", "currency", "payee", "splits");
        Map<String, Object> properties = (Map<String, Object>) transaction.get("properties");
        assertThat(properties.get("amount")).isEqualTo(Map.of("type", "number"));
        assertThat(properties.get("comment")).isEqualTo(Map.of("type", List.of("string", "null")));
        assertThat(properties.get("payee"))
                .isEqualTo(Map.of(
                        "oneOf",
                        List.of(Map.of("$ref", "#/components/schemas/NamedResponse"), Map.of("type", "null"))));
        assertThat((List<String>) ((Map<String, Object>) schemas.get("CategoryRow")).get("required"))
                .contains("ownAssigned", "ownActivity", "ownAvailable", "children");

        // Requests: optional fields stay optional, fields that clear a value accept null
        Map<String, Object> category = (Map<String, Object>) schemas.get("CategoryRequest");
        assertThat((List<String>) category.get("required")).containsExactly("kind", "name");
        Map<String, Object> color =
                (Map<String, Object>) ((Map<String, Object>) category.get("properties")).get("color");
        assertThat(color.get("type")).isEqualTo(List.of("string", "null"));
        assertThat((List<Object>) color.get("enum")).contains("blue").containsNull();

        // Uploaded files are binary strings
        Map<String, Object> upload = (Map<String, Object>)
                ((Map<String, Object>) spec.get("paths")).get("/api/v1/imports/privatbank/preview");
        Map<String, Object> body = (Map<String, Object>) ((Map<String, Object>) upload.get("post")).get("requestBody");
        Map<String, Object> multipart =
                (Map<String, Object>) ((Map<String, Object>) body.get("content")).get("multipart/form-data");
        Map<String, Object> fileSchema =
                (Map<String, Object>) ((Map<String, Object>) multipart.get("schema")).get("properties");
        assertThat(fileSchema.get("file")).isEqualTo(Map.of("type", "string", "format", "binary"));
        assertThat(schemas).doesNotContainKey("MultipartFile");
    }

    /** Drops the servers block, which depends on the host the spec was requested from. */
    @SuppressWarnings("unchecked")
    private String normalize(String json) {
        Map<String, Object> spec = jsonMapper.readValue(json, Map.class);
        spec.remove("servers");
        return PRINTER.writeValueAsString(spec).replace("\r\n", "\n") + "\n";
    }
}
