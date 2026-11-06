package com.andrewcorp.webwallet.bank.monobank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.bank.BankAuthMode;
import com.andrewcorp.webwallet.bank.BankConnection;
import com.andrewcorp.webwallet.bank.BankConnectionRepository;
import com.andrewcorp.webwallet.bank.BankProvider;
import com.andrewcorp.webwallet.bank.SyncMode;
import com.andrewcorp.webwallet.bank.monobank.MonobankClient.AccessRequest;
import com.andrewcorp.webwallet.bank.monobank.MonobankClient.BankAccount;
import com.andrewcorp.webwallet.bank.monobank.MonobankClient.ClientInfo;
import com.andrewcorp.webwallet.common.crypto.SecretCipher;
import com.andrewcorp.webwallet.ledger.transaction.TransactionRepository;
import com.andrewcorp.webwallet.user.User;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** Connecting Monobank through the bank app, with WebWallet registered as a service provider. */
class MonobankProviderTest extends AbstractIntegrationTest {

    private static final String REQUEST_ID = "req-1";
    private static final String CALLBACK_PREFIX = "https://wallet.test/api/v1/banks/monobank/auth/callback/";

    @MockitoBean
    private MonobankClient client;

    @MockitoBean
    private MonobankSigner signer;

    @Autowired
    private MonobankService service;

    @Autowired
    private BankConnectionRepository connections;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private SecretCipher cipher;

    @Autowired
    private TransactionTemplate tx;

    private User user;
    private String auth;
    private Object target;

    @BeforeEach
    void setUp() throws Exception {
        user = createVerifiedUser();
        auth = bearer(login(user.getEmail(), PASSWORD));
        when(signer.enabled()).thenReturn(true);
        // The service is a transactional proxy; the fields live in the target object
        target = AopTestUtils.getTargetObject(service);
        ReflectionTestUtils.setField(target, "publicUrl", "https://wallet.test");
        ReflectionTestUtils.setField(target, "providerWebhookSecret", "hook-secret");
        when(client.requestAccess(anyString()))
                .thenReturn(new AccessRequest(REQUEST_ID, "https://mbnk.app/auth/" + REQUEST_ID));
        when(client.clientInfoAsProvider(REQUEST_ID))
                .thenReturn(new ClientInfo(
                        "c1",
                        "Test User",
                        List.of(new BankAccount(
                                "mono-black", 1_000_000, 0, "black", 980, List.of("537541******1234"), "UA1")),
                        List.of()));
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(target, "publicUrl", "");
        ReflectionTestUtils.setField(target, "providerWebhookSecret", "");
    }

    private Map<String, Object> start(Map<String, Object> body) throws Exception {
        return json(postJson("/api/v1/banks/monobank/auth", body, auth), 200);
    }

    /** The secret part of the callback URL the bank was given. */
    private String callbackSecret() {
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(client).requestAccess(url.capture());
        assertThat(url.getValue()).startsWith(CALLBACK_PREFIX);
        return url.getValue().substring(CALLBACK_PREFIX.length());
    }

    private BankConnection connection() {
        return connections
                .findByUserIdAndProvider(user.getId(), BankProvider.MONOBANK)
                .orElseThrow();
    }

    @Test
    void theAppSeesTheFeature() throws Exception {
        mockMvc.perform(get("/api/v1/app/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features.monobankProvider").value(true));
    }

    @Test
    void theCallbackOfTheBankCreatesTheConnection() throws Exception {
        Map<String, Object> started = start(Map.of("syncMode", "DAILY"));
        assertThat(started.get("status")).isEqualTo("PENDING");
        assertThat(started.get("acceptUrl")).isEqualTo("https://mbnk.app/auth/req-1");
        String secret = callbackSecret();

        // A callback with another request id, or to an unknown URL, changes nothing
        mockMvc.perform(get(CALLBACK_PREFIX.substring("https://wallet.test".length()) + secret)
                        .header("X-Request-Id", "someone-else"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/banks/monobank/auth/callback/unknown").header("X-Request-Id", REQUEST_ID))
                .andExpect(status().isOk());
        assertThat(connections.findByUserIdAndProvider(user.getId(), BankProvider.MONOBANK))
                .isEmpty();

        mockMvc.perform(get("/api/v1/banks/monobank/auth/callback/" + secret).header("X-Request-Id", REQUEST_ID))
                .andExpect(status().isOk());

        BankConnection connection = connection();
        assertThat(connection.getAuthMode()).isEqualTo(BankAuthMode.PROVIDER);
        assertThat(connection.getSyncMode()).isEqualTo(SyncMode.DAILY);
        assertThat(cipher.decrypt(connection.getEncryptedToken())).isEqualTo(REQUEST_ID);
        mockMvc.perform(get("/api/v1/banks/monobank/auth/" + id(started)).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.connection.clientName").value("Test User"))
                .andExpect(jsonPath("$.connection.accounts[0].title").value("Monobank black *1234"));
        // A second callback does not connect again
        mockMvc.perform(get("/api/v1/banks/monobank/auth/callback/" + secret).header("X-Request-Id", REQUEST_ID))
                .andExpect(status().isOk());
        verify(client, times(1)).clientInfoAsProvider(REQUEST_ID);
    }

    @Test
    void theAppAsksTheBankWhenTheCallbackDoesNotCome() throws Exception {
        Map<String, Object> started = start(Map.of());
        String status = "/api/v1/banks/monobank/auth/" + id(started);
        when(client.accessGranted(REQUEST_ID)).thenReturn(false, true);

        mockMvc.perform(get(status).header("Authorization", auth))
                .andExpect(jsonPath("$.status").value("PENDING"));
        // Asked again only after a pause, however often the app polls
        mockMvc.perform(get(status).header("Authorization", auth))
                .andExpect(jsonPath("$.status").value("PENDING"));
        verify(client, times(1)).accessGranted(REQUEST_ID);

        tx.executeWithoutResult(s -> ReflectionTestUtils.setField(
                authRequest(id(started)), "checkedAt", Instant.now().minus(1, ChronoUnit.MINUTES)));
        mockMvc.perform(get(status).header("Authorization", auth))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.connection.syncMode").value("INTERVAL"));
        assertThat(connection().getAuthMode()).isEqualTo(BankAuthMode.PROVIDER);
    }

    @Test
    void anotherUserCannotSeeTheRequest() throws Exception {
        Map<String, Object> started = start(Map.of());

        mockMvc.perform(get("/api/v1/banks/monobank/auth/" + id(started)).header("Authorization", loginNewUser()))
                .andExpect(status().isNotFound());
    }

    @Test
    void theSynchronizationSignsAsTheProvider() throws Exception {
        start(Map.of());
        service.providerAuthCallback(callbackSecret(), REQUEST_ID);
        when(client.statementAsProvider(eq(REQUEST_ID), eq("mono-black"), any(), any()))
                .thenReturn(List.of());
        tx.executeWithoutResult(s -> {
            BankConnection connection = connection();
            connection.setLastRequestAt(Instant.now().minus(2, ChronoUnit.MINUTES));
            connection.setNextSyncAt(Instant.now());
        });

        assertThat(service.syncStep(connection().getId())).isTrue();

        verify(client).statementAsProvider(eq(REQUEST_ID), eq("mono-black"), any(), any());
        verify(client, never()).statement(anyString(), anyString(), any(), any());
    }

    @Test
    void theWebhookOfTheServiceImportsIntoConnectionsInTheWebhookMode() throws Exception {
        start(Map.of("syncMode", "WEBHOOK"));
        service.providerAuthCallback(callbackSecret(), REQUEST_ID);
        assertThat(connection().isWebhookRegistered()).isTrue();
        Map<String, Object> event = Map.of(
                "type",
                "StatementItem",
                "data",
                Map.of(
                        "account",
                        "mono-black",
                        "statementItem",
                        Map.of(
                                "id",
                                "op-1",
                                "time",
                                Instant.now().minus(1, ChronoUnit.HOURS).getEpochSecond(),
                                "description",
                                "Coffee",
                                "mcc",
                                5814,
                                "hold",
                                false,
                                "amount",
                                -6_500,
                                "operationAmount",
                                -6_500,
                                "currencyCode",
                                980,
                                "balance",
                                993_500)));

        // A wrong secret is ignored
        mockMvc.perform(postJson("/api/v1/banks/monobank/webhook/provider/wrong", event))
                .andExpect(status().isOk());
        assertThat(userTransactionCount()).isZero();

        mockMvc.perform(postJson("/api/v1/banks/monobank/webhook/provider/hook-secret", event))
                .andExpect(status().isOk());
        assertThat(userTransactionCount()).isEqualTo(1);
        // The test call of the bank
        mockMvc.perform(get("/api/v1/banks/monobank/webhook/provider/hook-secret"))
                .andExpect(status().isOk());
    }

    @Test
    void theWebhookOfTheServiceIsSetOnStartWhenConfigured() {
        service.registerProviderWebhook();

        verify(client).setProviderWebhook("https://wallet.test/api/v1/banks/monobank/webhook/provider/hook-secret");
    }

    @Test
    void withoutTheProviderKeyTheFlowIsNotThere() throws Exception {
        when(signer.enabled()).thenReturn(false);

        mockMvc.perform(postJson("/api/v1/banks/monobank/auth", Map.of(), auth)).andExpect(status().isNotFound());
        service.registerProviderWebhook();
        verify(client, never()).setProviderWebhook(anyString());
    }

    private MonobankAuthRequest authRequest(Long id) {
        return ((MonobankAuthRequestRepository) ReflectionTestUtils.getField(target, "authRequests"))
                .findById(id)
                .orElseThrow();
    }

    private long userTransactionCount() {
        return tx.execute(s -> transactions.findAll().stream()
                .filter(t -> t.getUserId().equals(user.getId()))
                .count());
    }
}
