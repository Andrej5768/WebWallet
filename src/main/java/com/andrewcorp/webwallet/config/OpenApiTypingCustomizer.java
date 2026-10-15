package com.andrewcorp.webwallet.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.springdoc.core.customizers.OpenApiCustomizer;

/**
 * Makes the spec precise enough for generated clients (the web client generates TypeScript types from it):
 *
 * <ul>
 *   <li>Every property of a response schema is required. Jackson writes every field of a response, also a null one,
 *       so a client can rely on the fields being present. Schemas also used in a request body are left as they are:
 *       there, optional fields may be omitted.
 *   <li>A field that may be null is declared with {@code @Schema(nullable = true)}, which OpenAPI 3.1 renders as a
 *       {@code "null"} type next to the type of the field. An enum then also lists {@code null}, and a reference to
 *       another schema, which cannot have a type next to it, becomes {@code oneOf} the reference and {@code null}.
 * </ul>
 */
class OpenApiTypingCustomizer implements OpenApiCustomizer {

    private static final String SCHEMA_REF_PREFIX = "#/components/schemas/";
    private static final String NULL_TYPE = "null";

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
            return;
        }
        Map<String, Schema> schemas = openApi.getComponents().getSchemas();
        requireResponseProperties(openApi, schemas);
        schemas.values().forEach(OpenApiTypingCustomizer::fixNullableProperties);
    }

    @SuppressWarnings("unchecked")
    private static void fixNullableProperties(Schema<?> schema) {
        if (schema.getProperties() == null) {
            return;
        }
        for (Map.Entry<String, Schema> entry : schema.getProperties().entrySet()) {
            Schema<Object> property = entry.getValue();
            if (property.getTypes() == null || !property.getTypes().contains(NULL_TYPE)) {
                continue;
            }
            if (property.get$ref() != null) {
                entry.setValue(new JsonSchema()
                        .oneOf(List.of(
                                new JsonSchema().$ref(property.get$ref()), new JsonSchema().typesItem(NULL_TYPE))));
            } else if (property.getEnum() != null && !property.getEnum().contains(null)) {
                List<Object> values = new ArrayList<>(property.getEnum());
                values.add(null);
                property.setEnum(values);
            }
        }
    }

    private static void requireResponseProperties(OpenAPI openApi, Map<String, Schema> schemas) {
        Set<String> requests = new HashSet<>();
        Set<String> responses = new HashSet<>();
        operations(openApi).forEach(operation -> {
            if (operation.getRequestBody() != null) {
                collect(operation.getRequestBody().getContent(), schemas, requests);
            }
            if (operation.getResponses() != null) {
                for (ApiResponse response : operation.getResponses().values()) {
                    collect(response.getContent(), schemas, responses);
                }
            }
        });
        responses.removeAll(requests);
        for (String name : responses) {
            Schema<?> schema = schemas.get(name);
            if (schema.getProperties() != null && !schema.getProperties().isEmpty()) {
                schema.setRequired(
                        new ArrayList<>(new TreeSet<>(schema.getProperties().keySet())));
            }
        }
    }

    private static Stream<Operation> operations(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return Stream.empty();
        }
        return openApi.getPaths().values().stream()
                .map(PathItem::readOperations)
                .flatMap(List::stream);
    }

    private static void collect(Content content, Map<String, Schema> schemas, Set<String> names) {
        if (content != null) {
            content.values().stream()
                    .map(MediaType::getSchema)
                    .filter(Objects::nonNull)
                    .forEach(schema -> collect(schema, schemas, names));
        }
    }

    /** Adds the names of the component schemas the schema refers to, directly or through other schemas. */
    @SuppressWarnings("unchecked")
    private static void collect(Schema<?> schema, Map<String, Schema> schemas, Set<String> names) {
        if (schema == null) {
            return;
        }
        String ref = schema.get$ref();
        if (ref != null && ref.startsWith(SCHEMA_REF_PREFIX)) {
            String name = ref.substring(SCHEMA_REF_PREFIX.length());
            if (names.add(name)) {
                collect(schemas.get(name), schemas, names);
            }
            return;
        }
        if (schema.getProperties() != null) {
            schema.getProperties().values().forEach(property -> collect(property, schemas, names));
        }
        collect(schema.getItems(), schemas, names);
        if (schema.getAdditionalProperties() instanceof Schema<?> additional) {
            collect(additional, schemas, names);
        }
        Stream.of(schema.getAllOf(), schema.getAnyOf(), schema.getOneOf())
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .forEach(part -> collect((Schema<?>) part, schemas, names));
    }
}
