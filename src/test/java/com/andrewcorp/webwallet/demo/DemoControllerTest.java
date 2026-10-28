package com.andrewcorp.webwallet.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.auth.dto.TokenResponse;
import com.andrewcorp.webwallet.user.User;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class DemoControllerTest extends AbstractIntegrationTest {

    @Autowired
    private DemoService demoService;

    @Test
    void demoStartsASessionInAFilledSandbox() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(
                        post("/api/v1/auth/demo").header("Accept-Language", "uk"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andReturn()
                .getResponse();
        refreshCookie(response);
        String authorization = bearer(tokens(response));

        Map<String, Object> me = json(get("/api/v1/me").header("Authorization", authorization), 200);
        assertThat(me.get("demo")).isEqualTo(true);
        assertThat(me.get("demoExpiresAt")).isNotNull();
        assertThat(me.get("locale")).isEqualTo("uk");
        assertThat((String) me.get("email")).endsWith("@demo.invalid");

        List<Map<String, Object>> accounts = jsonList(get("/api/v1/accounts").header("Authorization", authorization));
        assertThat(accounts).extracting(account -> account.get("name")).contains("Чорна картка", "Готівка", "Депозит");

        Map<String, Object> page =
                json(get("/api/v1/transactions").param("size", "1").header("Authorization", authorization), 200);
        assertThat(number(page.get("totalItems"))).isGreaterThan(80);

        Map<String, Object> summary = json(get("/api/v1/accounts/summary").header("Authorization", authorization), 200);
        assertThat(number(summary.get("netWorth"))).isGreaterThan(0);

        List<Map<String, Object>> planned = jsonList(get("/api/v1/recurring").header("Authorization", authorization));
        assertThat(planned).hasSize(4);
    }

    @Test
    void demoDataIsInEnglishForOtherLanguages() throws Exception {
        String authorization = bearer(tokens(mockMvc.perform(startDemo())
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()));

        List<Map<String, Object>> accounts = jsonList(get("/api/v1/accounts").header("Authorization", authorization));
        assertThat(accounts).extracting(account -> account.get("name")).contains("Black card", "Cash");
    }

    @Test
    void androidGetsTheRefreshTokenInTheBody() throws Exception {
        mockMvc.perform(asAndroid(startDemo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").isString());
    }

    @Test
    void demoCannotReachOutside() throws Exception {
        String authorization =
                bearer(tokens(mockMvc.perform(startDemo()).andReturn().getResponse()));

        mockMvc.perform(get("/api/v1/me/export").header("Authorization", authorization))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DEMO_RESTRICTED"));
        mockMvc.perform(postJson("/api/v1/banks/monobank", Map.of("token", "x"), authorization))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DEMO_RESTRICTED"));
        // Inside the wallet everything works
        mockMvc.perform(postJson(
                        "/api/v1/accounts", Map.of("name", "New", "type", "CASH", "currency", "UAH"), authorization))
                .andExpect(status().isCreated());
    }

    @Test
    void claimingTurnsTheSandboxIntoAnAccountAndSendsTheVerification() throws Exception {
        TokenResponse tokens = tokens(mockMvc.perform(startDemo()).andReturn().getResponse());
        String authorization = bearer(tokens);

        mockMvc.perform(postJson(
                        "/api/v1/me/demo/claim",
                        Map.of("email", "Keeper@Test.com", "password", "secret-pass-1", "firstName", "Ada"),
                        authorization))
                .andExpect(status().isNoContent());

        Map<String, Object> me = json(get("/api/v1/me").header("Authorization", authorization), 200);
        assertThat(me.get("demo")).isEqualTo(false);
        assertThat(me.get("email")).isEqualTo("keeper@test.com");
        assertThat(me.get("firstName")).isEqualTo("Ada");
        assertThat(me.get("emailVerified")).isEqualTo(false);
        assertThat(lastMail().getTo()).containsExactly("keeper@test.com");
        // The data stays and the export is open now
        mockMvc.perform(get("/api/v1/me/export").header("Authorization", authorization))
                .andExpect(status().isOk());
        // The verification link makes the account whole; then the password signs in
        mockMvc.perform(postJson("/api/v1/auth/verify-email", Map.of("token", tokenFromLastMail())))
                .andExpect(status().isNoContent());
        assertThat(login("keeper@test.com", "secret-pass-1").accessToken()).isNotBlank();
    }

    @Test
    void claimRefusesATakenEmailAndARealAccount() throws Exception {
        User existing = createVerifiedUser();
        String demo = bearer(tokens(mockMvc.perform(startDemo()).andReturn().getResponse()));
        mockMvc.perform(postJson(
                        "/api/v1/me/demo/claim",
                        Map.of("email", existing.getEmail(), "password", "secret-pass-1", "firstName", "Ada"),
                        demo))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("email"));

        String real = loginNewUser();
        mockMvc.perform(postJson(
                        "/api/v1/me/demo/claim",
                        Map.of("email", "other@test.com", "password", "secret-pass-1", "firstName", "Ada"),
                        real))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DEMO_RESTRICTED"));
    }

    @Test
    void expiredSandboxesAreDeletedWithTheirData() throws Exception {
        String authorization =
                bearer(tokens(mockMvc.perform(startDemo()).andReturn().getResponse()));
        Map<String, Object> me = json(get("/api/v1/me").header("Authorization", authorization), 200);
        Long userId = id(me);

        assertThat(demoService.deleteExpired()).isZero();
        users.findById(userId).ifPresent(user -> {
            user.setDemoExpiresAt(user.getDemoExpiresAt().minus(Duration.ofDays(2)));
            users.save(user);
        });
        assertThat(demoService.deleteExpired()).isEqualTo(1);
        assertThat(users.findById(userId)).isEmpty();
        mockMvc.perform(get("/api/v1/me").header("Authorization", authorization))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void startsArePerClientIpLimited() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(startDemo().with(request -> {
                        request.setRemoteAddr("10.9.9.9");
                        return request;
                    }))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(startDemo().with(request -> {
                    request.setRemoteAddr("10.9.9.9");
                    return request;
                }))
                .andExpect(status().isTooManyRequests());
    }

    /** Each start from a client IP of its own, since the service limits starts per IP. */
    private static MockHttpServletRequestBuilder startDemo() {
        String ip = "10.0." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250);
        return post("/api/v1/auth/demo").with(request -> {
            request.setRemoteAddr(ip);
            return request;
        });
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> jsonList(org.springframework.test.web.servlet.RequestBuilder request)
            throws Exception {
        String body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return jsonMapper.readValue(body, List.class);
    }
}
