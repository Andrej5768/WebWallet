package com.andrewcorp.webwallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.andrewcorp.webwallet.auth.AuthRateLimiter;
import com.andrewcorp.webwallet.auth.dto.TokenResponse;
import com.andrewcorp.webwallet.currency.ExchangeRateService;
import com.andrewcorp.webwallet.currency.NbuClient;
import com.andrewcorp.webwallet.investment.market.ListingSearch;
import com.andrewcorp.webwallet.mail.MailConfig;
import com.andrewcorp.webwallet.monitoring.RequestRateLimitFilter;
import com.andrewcorp.webwallet.user.User;
import com.andrewcorp.webwallet.user.UserRepository;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs against the full application with PostgreSQL in Testcontainers. Requests commit for real (no test
 * transaction), so the tables are cleaned before every test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {

    protected static final String PASSWORD = "password123";
    protected static final String REFRESH_COOKIE = "refresh_token";
    private static final Pattern TOKEN_IN_LINK = Pattern.compile("token=([A-Za-z0-9_-]+)");

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JsonMapper jsonMapper;

    @Autowired
    protected UserRepository users;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuthRateLimiter rateLimiter;

    @Autowired
    private RequestRateLimitFilter requestRateLimit;

    @Autowired
    private ExchangeRateService exchangeRates;

    @Autowired
    private ListingSearch listingSearch;

    @MockitoBean
    protected JavaMailSender mailSender;

    @MockitoBean
    protected NbuClient nbuClient;

    /** Emails are sent synchronously, so that a test can check them right after the request. */
    @TestBean(name = MailConfig.MAIL_EXECUTOR)
    private Executor mailExecutor;

    static Executor mailExecutor() {
        return new SyncTaskExecutor();
    }

    /** A test can move the time of the application with {@link TestClock#set}; it is reset before every test. */
    @TestBean(name = "clock")
    private Clock clock;

    static Clock clock() {
        return TestClock.INSTANCE;
    }

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("truncate table users, exchange_rates cascade");
        TestClock.INSTANCE.reset();
        clearInvocations(mailSender);
        rateLimiter.reset();
        requestRateLimit.reset();
        exchangeRates.forgetMissingRates();
        listingSearch.reset();
    }

    protected MockHttpServletRequestBuilder postJson(String url, Object body) {
        return post(url).contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body));
    }

    /** Creates a user directly in the database. */
    protected User createUser(String email, boolean emailVerified) {
        User user = new User(email, passwordEncoder.encode(PASSWORD), "John", "Doe", "en");
        user.setEmailVerified(emailVerified);
        return users.save(user);
    }

    protected User createVerifiedUser() {
        return createUser("user-" + UUID.randomUUID() + "@test.com", true);
    }

    protected TokenResponse login(String email, String password) throws Exception {
        return tokens(loginResponse(email, password));
    }

    /** Logs in and returns the response: the access token in the body and the refresh token in a cookie. */
    protected MockHttpServletResponse loginResponse(String email, String password) throws Exception {
        return mockMvc.perform(postJson("/api/v1/auth/login", Map.of("email", email, "password", password)))
                .andReturn()
                .getResponse();
    }

    protected TokenResponse tokens(MockHttpServletResponse response) throws Exception {
        return jsonMapper.readValue(response.getContentAsString(), TokenResponse.class);
    }

    /** The refresh token cookie set by the response, as the browser sends it back. */
    protected static Cookie refreshCookie(MockHttpServletResponse response) {
        Cookie cookie = response.getCookie(REFRESH_COOKIE);
        assertThat(cookie).as("refresh token cookie").isNotNull();
        return new Cookie(REFRESH_COOKIE, cookie.getValue());
    }

    protected static MockHttpServletRequestBuilder refresh(Cookie refreshCookie) {
        return post("/api/v1/auth/refresh").cookie(refreshCookie);
    }

    /** Logs in a new verified user and returns the Authorization header value. */
    protected String loginNewUser() throws Exception {
        return bearer(login(createVerifiedUser().getEmail(), PASSWORD));
    }

    protected MockHttpServletRequestBuilder postJson(String url, Object body, String authorization) {
        return postJson(url, body).header("Authorization", authorization);
    }

    protected MockHttpServletRequestBuilder putJson(String url, Object body, String authorization) {
        return put(url).contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body))
                .header("Authorization", authorization);
    }

    /** Performs the request, expects the status and returns the JSON response as a map. */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> json(RequestBuilder request, int expectedStatus) throws Exception {
        String body = mockMvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return body.isEmpty() ? Map.of() : jsonMapper.readValue(body, Map.class);
    }

    /** Creates an account by the API and returns its id. */
    protected Long createAccount(String authorization, String name, String type, String currency, double openingBalance)
            throws Exception {
        return id(json(
                postJson(
                        "/api/v1/accounts",
                        Map.of("name", name, "type", type, "currency", currency, "openingBalance", openingBalance),
                        authorization),
                201));
    }

    /** Creates a category by the API, under the parent when there is one, and returns its id. */
    protected Long createCategory(String authorization, String name, String kind, Long parentId) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("name", name, "kind", kind));
        body.put("parentId", parentId);
        return id(json(postJson("/api/v1/categories", body, authorization), 201));
    }

    /** A number of a JSON response. */
    protected static double number(Object value) {
        return ((Number) value).doubleValue();
    }

    protected static Long id(Map<String, Object> json) {
        return ((Number) json.get("id")).longValue();
    }

    protected static String bearer(TokenResponse tokens) {
        return "Bearer " + tokens.accessToken();
    }

    protected List<SimpleMailMessage> sentMails() {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, atLeastOnce()).send(captor.capture());
        return captor.getAllValues();
    }

    protected SimpleMailMessage lastMail() {
        List<SimpleMailMessage> mails = sentMails();
        return mails.get(mails.size() - 1);
    }

    /** Extracts the token from the link in the last sent email. */
    protected String tokenFromLastMail() {
        Matcher matcher = TOKEN_IN_LINK.matcher(lastMail().getText());
        assertThat(matcher.find()).as("email contains a token link").isTrue();
        return matcher.group(1);
    }
}
