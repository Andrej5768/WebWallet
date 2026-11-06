package com.andrewcorp.webwallet.bank.monobank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class MonobankClientTest {

    private static final Instant NOW = Instant.ofEpochSecond(1790000000);

    private MockRestServiceServer server;
    private MonobankClient client;
    private KeyPair keys;

    @BeforeEach
    void setUp() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        keys = MonobankSignerTest.keyPair();
        client = new MonobankClient(
                builder,
                "https://mono.test",
                new MonobankSigner("key-1", MonobankSignerTest.pem(keys)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** The signature header verifies with the public key against the time, the request id and the path. */
    private org.springframework.test.web.client.RequestMatcher signed(String requestId, String path) {
        return request -> {
            String signature = request.getHeaders().getFirst("X-Sign");
            String text = NOW.getEpochSecond() + (requestId == null ? "" : requestId) + path;
            try {
                assertThat(MonobankSignerTest.verifies(keys, text, signature))
                        .as("signature of " + text)
                        .isTrue();
            } catch (AssertionError e) {
                throw e;
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        };
    }

    @Test
    void providerRequestsAreSignedInsteadOfCarryingAToken() {
        server.expect(requestTo("https://mono.test/personal/client-info"))
                .andExpect(header("X-Key-Id", "key-1"))
                .andExpect(header("X-Time", "1790000000"))
                .andExpect(header("X-Request-Id", "req-1"))
                .andExpect(signed("req-1", "/personal/client-info"))
                .andExpect(request -> assertThat(request.getHeaders().containsHeader("X-Token"))
                        .isFalse())
                .andRespond(withSuccess("{\"name\": \"Test User\", \"accounts\": []}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://mono.test/personal/statement/acc1/1790000000/1790086400"))
                .andExpect(header("X-Request-Id", "req-1"))
                .andExpect(signed("req-1", "/personal/statement/acc1/1790000000/1790086400"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.clientInfoAsProvider("req-1").name()).isEqualTo("Test User");
        assertThat(client.statementAsProvider(
                        "req-1", "acc1", Instant.ofEpochSecond(1790000000), Instant.ofEpochSecond(1790086400)))
                .isEmpty();
        server.verify();
    }

    @Test
    void accessRequestAndItsCheck() {
        server.expect(requestTo("https://mono.test/personal/auth/request"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Key-Id", "key-1"))
                .andExpect(header("X-Callback", "https://app.test/callback/s"))
                .andExpect(signed(null, "/personal/auth/request"))
                .andRespond(withSuccess(
                        "{\"tokenRequestId\": \"req-1\", \"acceptUrl\": \"https://mbnk.app/auth/req-1\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://mono.test/personal/auth/request"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(signed("req-1", "/personal/auth/request"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo("https://mono.test/personal/auth/request"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        MonobankClient.AccessRequest access = client.requestAccess("https://app.test/callback/s");

        assertThat(access.tokenRequestId()).isEqualTo("req-1");
        assertThat(access.acceptUrl()).isEqualTo("https://mbnk.app/auth/req-1");
        assertThat(client.accessGranted("req-1")).isFalse();
        assertThat(client.accessGranted("req-1")).isTrue();
        server.verify();
    }

    @Test
    void providerWebhook() {
        server.expect(requestTo("https://mono.test/personal/corp/webhook"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Key-Id", "key-1"))
                .andExpect(request -> assertThat(request.getHeaders().getFirst("X-Request-Id"))
                        .isNotBlank())
                .andExpect(signed(null, "/personal/corp/webhook"))
                .andExpect(content().json("{\"webHookUrl\": \"https://app.test/hook\"}"))
                .andRespond(withSuccess());

        client.setProviderWebhook("https://app.test/hook");

        server.verify();
    }

    @Test
    void clientInfo() {
        server.expect(requestTo("https://mono.test/personal/client-info"))
                .andExpect(header("X-Token", "token"))
                .andRespond(withSuccess("""
                        {"clientId": "c1", "name": "Test User", "webHookUrl": "", "permissions": "psfj",
                         "accounts": [{"id": "acc1", "sendId": "s", "balance": 1234567, "creditLimit": 0, "type": "black",
                                       "currencyCode": 980, "cashbackType": "UAH", "maskedPan": ["537541******1234"],
                                       "iban": "UA00"}],
                         "jars": [{"id": "jar1", "sendId": "j", "title": "Trip", "description": "", "currencyCode": 980,
                                   "balance": 50000, "goal": 100000}]}""", MediaType.APPLICATION_JSON));

        MonobankClient.ClientInfo info = client.clientInfo("token");

        assertThat(info.name()).isEqualTo("Test User");
        assertThat(info.accounts()).singleElement().satisfies(account -> {
            assertThat(account.balance()).isEqualTo(1234567);
            assertThat(account.maskedPan()).containsExactly("537541******1234");
        });
        assertThat(info.jars())
                .singleElement()
                .satisfies(jar -> assertThat(jar.title()).isEqualTo("Trip"));
    }

    @Test
    void statementUsesUnixSeconds() {
        server.expect(requestTo("https://mono.test/personal/statement/acc1/1790000000/1790086400"))
                .andRespond(withSuccess("""
                        [{"id": "op1", "time": 1790050000, "description": "Silpo", "mcc": 5411, "originalMcc": 5411,
                          "hold": false, "amount": -30051, "operationAmount": -30051, "currencyCode": 980,
                          "commissionRate": 0, "cashbackAmount": 0, "balance": 1000000, "comment": "food"}]""", MediaType.APPLICATION_JSON));

        List<MonobankClient.StatementItem> items =
                client.statement("token", "acc1", Instant.ofEpochSecond(1790000000), Instant.ofEpochSecond(1790086400));

        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.amount()).isEqualTo(-30051);
            assertThat(item.mcc()).isEqualTo(5411);
            assertThat(item.comment()).isEqualTo("food");
        });
    }

    @Test
    void setWebhook() {
        server.expect(requestTo("https://mono.test/personal/webhook"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"webHookUrl\": \"https://app.test/hook\"}"))
                .andRespond(withSuccess());

        client.setWebhook("token", "https://app.test/hook");

        server.verify();
    }

    @Test
    void errors() {
        server.expect(requestTo("https://mono.test/personal/client-info")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> client.clientInfo("bad"))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_EXTERNAL_TOKEN));

        server.reset();
        server.expect(requestTo("https://mono.test/personal/client-info"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> client.clientInfo("token"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE));
    }
}
