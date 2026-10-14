package com.andrewcorp.webwallet.bank.monobank;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MonobankClientTest {

    private MockRestServiceServer server;
    private MonobankClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MonobankClient(builder, "https://mono.test");
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
        assertThat(info.jars()).singleElement().satisfies(jar -> assertThat(jar.title()).isEqualTo("Trip"));
    }

    @Test
    void statementUsesUnixSeconds() {
        server.expect(requestTo("https://mono.test/personal/statement/acc1/1790000000/1790086400"))
                .andRespond(withSuccess("""
                        [{"id": "op1", "time": 1790050000, "description": "Silpo", "mcc": 5411, "originalMcc": 5411,
                          "hold": false, "amount": -30051, "operationAmount": -30051, "currencyCode": 980,
                          "commissionRate": 0, "cashbackAmount": 0, "balance": 1000000, "comment": "food"}]""",
                        MediaType.APPLICATION_JSON));

        List<MonobankClient.StatementItem> items = client.statement("token", "acc1",
                Instant.ofEpochSecond(1790000000), Instant.ofEpochSecond(1790086400));

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
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_EXTERNAL_TOKEN));

        server.reset();
        server.expect(requestTo("https://mono.test/personal/client-info")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> client.clientInfo("token"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE));
    }
}
