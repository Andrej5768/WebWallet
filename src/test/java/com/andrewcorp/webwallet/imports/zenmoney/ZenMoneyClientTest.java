package com.andrewcorp.webwallet.imports.zenmoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ZenMoneyClientTest {

    private MockRestServiceServer server;
    private ZenMoneyClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1791815000), ZoneOffset.UTC);
        client = new ZenMoneyClient(builder, "https://zen.test", clock);
    }

    @Test
    void fetchesFullExport() {
        server.expect(requestTo("https://zen.test/v8/diff/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer secret"))
                .andExpect(content().json("{\"currentClientTimestamp\":1791815000,\"serverTimestamp\":0}"))
                .andRespond(withSuccess("""
                        {"serverTimestamp": 1, "instrument": [{"id": 4, "shortTitle": "UAH"}],
                         "account": [], "transaction": [], "unknownEntity": []}""", MediaType.APPLICATION_JSON));

        ZenMoneyDump dump = client.fetchAll("secret");

        assertThat(dump.instrument())
                .singleElement()
                .satisfies(i -> assertThat(i.shortTitle()).isEqualTo("UAH"));
    }

    @Test
    void rejectedToken() {
        server.expect(requestTo("https://zen.test/v8/diff/")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.fetchAll("bad"))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_EXTERNAL_TOKEN));
    }

    @Test
    void serviceErrors() {
        server.expect(requestTo("https://zen.test/v8/diff/")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> client.fetchAll("x"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE));

        server.reset();
        server.expect(requestTo("https://zen.test/v8/diff/")).andRespond(withServerError());
        assertThatThrownBy(() -> client.fetchAll("x"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE));
    }
}
