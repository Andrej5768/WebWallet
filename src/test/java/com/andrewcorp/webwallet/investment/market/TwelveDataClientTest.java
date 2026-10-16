package com.andrewcorp.webwallet.investment.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.andrewcorp.webwallet.investment.market.MarketDataProvider.DailyClose;
import com.andrewcorp.webwallet.investment.market.MarketDataProvider.Listing;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class TwelveDataClientTest {

    private MockRestServiceServer server;
    private TwelveDataClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TwelveDataClient(builder, "https://td.test", "key");
    }

    @Test
    void searchesSymbols() {
        server.expect(requestTo("https://td.test/symbol_search?symbol=AAPL&outputsize=20&apikey=key"))
                .andRespond(withSuccess("""
                        {"data":[{"symbol":"AAPL","instrument_name":"Apple Inc","exchange":"NASDAQ","mic_code":"XNGS",
                          "exchange_timezone":"America/New_York","instrument_type":"Common Stock",
                          "country":"United States","currency":"USD"}],"status":"ok"}""", MediaType.APPLICATION_JSON));

        assertThat(client.search("AAPL"))
                .containsExactly(
                        new Listing("AAPL", "Apple Inc", "NASDAQ", "XNGS", "United States", "USD", "Common Stock"));
        assertThat(client.enabled()).isTrue();
    }

    @Test
    void loadsUnadjustedDailyCloses() {
        server.expect(requestTo("https://td.test/time_series?symbol=AAPL&mic_code=XNGS&interval=1day"
                        + "&start_date=2026-10-12&end_date=2026-10-16&order=asc&adjust=none&apikey=key"))
                .andRespond(withSuccess("""
                        {"meta":{"symbol":"AAPL","interval":"1day","currency":"USD"},
                         "values":[{"datetime":"2026-10-13","close":"220.5"},{"datetime":"2026-10-12","close":"218"}],
                         "status":"ok"}""", MediaType.APPLICATION_JSON));

        List<DailyClose> closes = client.closes("AAPL", "XNGS", LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 16));

        assertThat(closes)
                .extracting(DailyClose::date)
                .containsExactly(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 13));
        assertThat(closes.get(1).close()).isEqualByComparingTo("220.5");
    }

    @Test
    void treatsNoDataAsEmptyAndOtherErrorsAsFailures() {
        server.expect(requestTo("https://td.test/time_series?symbol=AAPL&mic_code=&interval=1day"
                        + "&start_date=2026-10-17&end_date=2026-10-18&order=asc&adjust=none&apikey=key"))
                .andRespond(withSuccess("""
                        {"code":404,"message":"No data is available on the specified dates","status":"error"}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://td.test/time_series?symbol=AAPL&mic_code=&interval=1day"
                        + "&start_date=2026-10-17&end_date=2026-10-18&order=asc&adjust=none&apikey=key"))
                .andRespond(withSuccess("""
                        {"code":429,"message":"API request limit reached","status":"error"}""", MediaType.APPLICATION_JSON));

        assertThat(client.closes("AAPL", null, LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 18)))
                .isEmpty();
        assertThatThrownBy(() -> client.closes("AAPL", null, LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 18)))
                .isInstanceOf(RestClientException.class);
    }

    @Test
    void isDisabledWithoutAKey() {
        assertThat(new TwelveDataClient(RestClient.builder(), "https://td.test", "").enabled())
                .isFalse();
    }
}
