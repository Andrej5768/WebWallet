package com.andrewcorp.webwallet.ledger;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Tags and payees have the same API. */
class TagAndPayeeControllerTest extends AbstractIntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/tags", "/api/v1/payees"})
    void crud(String url) throws Exception {
        String auth = loginNewUser();

        Long id = id(json(postJson(url, Map.of("name", " Vacation "), auth), 201));
        json(postJson(url, Map.of("name", "Another"), auth), 201);

        mockMvc.perform(get(url).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Another"))
                .andExpect(jsonPath("$[1].name").value("Vacation"));

        // Names are unique per user, ignoring case
        mockMvc.perform(postJson(url, Map.of("name", "vacation"), auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        mockMvc.perform(putJson(url + "/" + id, Map.of("name", "ANOTHER"), auth))
                .andExpect(status().isBadRequest());
        // Changing only the case of the own name is fine
        mockMvc.perform(putJson(url + "/" + id, Map.of("name", "VACATION"), auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("VACATION"));
        // Another user can use the same name and cannot see or change the item
        String otherAuth = loginNewUser();
        json(postJson(url, Map.of("name", "Vacation"), otherAuth), 201);
        mockMvc.perform(putJson(url + "/" + id, Map.of("name", "Stolen"), otherAuth))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete(url + "/" + id).header("Authorization", auth))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete(url + "/" + id).header("Authorization", auth))
                .andExpect(status().isNotFound());
    }
}
