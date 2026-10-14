package com.andrewcorp.webwallet.currency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NbuClientTest {

    private MockRestServiceServer server;
    private NbuClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new NbuClient(builder, "https://nbu.test");
    }

    @Test
    void ratesOnDate() {
        server.expect(requestTo("https://nbu.test/NBUStatService/v1/statdirectory/exchange?date=20261013&json"))
                .andRespond(withSuccess("""
                        [{"r030":840,"txt":"Долар США","rate":44.6819,"cc":"USD","exchangedate":"13.10.2026","special":null},
                         {"r030":978,"txt":"Євро","rate":48.12,"cc":"EUR","exchangedate":"13.10.2026","special":null}]""",
                        MediaType.APPLICATION_JSON));

        List<ExchangeRate> rates = client.ratesOn(LocalDate.of(2026, 10, 13));

        assertThat(rates).extracting(ExchangeRate::getCurrency).containsExactly("USD", "EUR");
        assertThat(rates.get(0).getRate()).isEqualByComparingTo("44.6819");
        assertThat(rates.get(0).getDate()).isEqualTo(LocalDate.of(2026, 10, 13));
    }

    @Test
    void historyUsesRatePerUnit() {
        server.expect(requestTo("https://nbu.test/NBU_Exchange/exchange_site?start=20261001&end=20261002"
                        + "&valcode=jpy&sort=exchangedate&order=asc&json"))
                .andRespond(withSuccess("""
                        [{"exchangedate":"01.10.2026","cc":"JPY","rate":27.5,"units":100,"rate_per_unit":0.275},
                         {"exchangedate":"02.10.2026","cc":"JPY","rate":27.6,"units":100,"rate_per_unit":0.276}]""",
                        MediaType.APPLICATION_JSON));

        List<ExchangeRate> rates = client.history("JPY", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2));

        assertThat(rates).extracting(ExchangeRate::getDate)
                .containsExactly(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2));
        assertThat(rates.get(1).getRate()).isEqualByComparingTo("0.276");
    }
}
