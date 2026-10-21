package com.andrewcorp.webwallet.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NotificationSettingsControllerTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/notifications/settings";

    @Test
    void everythingIsOnAtNineByDefault() throws Exception {
        String auth = loginNewUser();

        Map<String, Object> settings = json(get(URL).header("Authorization", auth), 200);

        assertThat(settings)
                .containsEntry("hour", 9)
                .containsEntry("recurring", true)
                .containsEntry("monthly", true);
    }

    @Test
    void updatesTheSettings() throws Exception {
        String auth = loginNewUser();
        Map<String, Object> body = new HashMap<>(json(get(URL).header("Authorization", auth), 200));
        body.put("hour", 20);
        body.put("budgets", false);

        json(putJson(URL, body, auth), 200);

        assertThat(json(get(URL).header("Authorization", auth), 200))
                .containsEntry("hour", 20)
                .containsEntry("budgets", false)
                .containsEntry("weekly", true);
    }

    @Test
    void hourIsOfADay() throws Exception {
        String auth = loginNewUser();
        Map<String, Object> body = new HashMap<>(json(get(URL).header("Authorization", auth), 200));
        body.put("hour", 24);

        json(putJson(URL, body, auth), 400);
    }
}
