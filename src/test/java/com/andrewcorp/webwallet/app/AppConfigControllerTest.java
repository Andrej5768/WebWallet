package com.andrewcorp.webwallet.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.config.AppProperties;
import com.andrewcorp.webwallet.investment.market.ListingSearch;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

class AppConfigControllerTest extends AbstractIntegrationTest {

    @Autowired
    private AppProperties properties;

    @Autowired
    private ListingSearch listingSearch;

    @Test
    void returnsTheDefaultsWithoutAToken() throws Exception {
        mockMvc.perform(get("/api/v1/app/config"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=300, public"))
                .andExpect(jsonPath("$.android.minSupportedVersionCode").value(1))
                .andExpect(jsonPath("$.android.latestVersionCode").value(1))
                .andExpect(jsonPath("$.android.storeUrl")
                        .value("https://play.google.com/store/apps/details?id=org.andrewdev.webwallet"))
                .andExpect(jsonPath("$.features.push").value(false));
    }

    @Test
    void ignoresAnInvalidBearerToken() throws Exception {
        mockMvc.perform(get("/api/v1/app/config").header(HttpHeaders.AUTHORIZATION, "Bearer garbage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.android.minSupportedVersionCode").value(1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theFeaturesMatchWhatTheWebIsTold() throws Exception {
        Map<String, Object> config = json(get("/api/v1/app/config"), 200);
        Map<String, Object> authOptions = json(get("/api/v1/auth/options"), 200);
        Map<String, Object> features = (Map<String, Object>) config.get("features");

        assertThat(features.get("googleSignIn")).isEqualTo(authOptions.get("googleSignIn"));
        assertThat(features.get("googleSignIn")).isEqualTo(false);
        assertThat(features.get("telegram")).isEqualTo(properties.telegram().enabled());
        assertThat(features.get("marketSearch")).isEqualTo(listingSearch.enabled());
    }
}
