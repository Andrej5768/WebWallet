package com.andrewcorp.webwallet.common.error;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.auth.dto.TokenResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ErrorHandlingTest extends AbstractIntegrationTest {

    @Test
    void malformedJsonIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void validationMessagesAreLocalized() throws Exception {
        mockMvc.perform(postJson("/api/v1/auth/login", Map.of("email", "", "password", "x"))
                        .header("Accept-Language", "uk"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Дані запиту не пройшли перевірку"))
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
    }

    @Test
    void unknownEndpointIsNotFoundWithCode() throws Exception {
        TokenResponse tokens = login(createVerifiedUser().getEmail(), PASSWORD);

        mockMvc.perform(get("/api/v1/unknown").header("Authorization", bearer(tokens)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void wrongMethodIsMethodNotAllowed() throws Exception {
        mockMvc.perform(get("/api/v1/auth/login"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void healthAndApiDocsArePublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/auth/login']").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearer.scheme").value("bearer"));
    }

    @Test
    void corsPreflightIsAllowedForTheFrontend() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header()
                        .string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }
}
