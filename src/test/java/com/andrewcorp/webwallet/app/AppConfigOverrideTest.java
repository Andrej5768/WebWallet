package com.andrewcorp.webwallet.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(
        properties = {
            "app.android.min-supported-version-code=5",
            "app.android.latest-version-code=7",
            "app.android.store-url=https://example.com/app"
        })
class AppConfigOverrideTest extends AbstractIntegrationTest {

    @Test
    void returnsTheConfiguredVersionsAndStore() throws Exception {
        mockMvc.perform(get("/api/v1/app/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.android.minSupportedVersionCode").value(5))
                .andExpect(jsonPath("$.android.latestVersionCode").value(7))
                .andExpect(jsonPath("$.android.storeUrl").value("https://example.com/app"));
    }
}
