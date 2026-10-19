package com.andrewcorp.webwallet.investment.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.andrewcorp.webwallet.investment.Sector;
import com.andrewcorp.webwallet.investment.market.DividendProvider.Dividend;
import com.andrewcorp.webwallet.investment.market.SectorProvider.SectorWeight;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class AlphaVantageClientTest {

    private MockRestServiceServer server;
    private AlphaVantageClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AlphaVantageClient(builder, "https://av.test", "key");
    }

    @Test
    void readsDividendsWithUnknownDates() {
        server.expect(requestTo("https://av.test/query?function=DIVIDENDS&symbol=IBM&apikey=key"))
                .andRespond(withSuccess("""
                        {"symbol":"IBM","data":[
                          {"ex_dividend_date":"2026-08-10","declaration_date":"2026-07-22","record_date":"2026-08-10",
                           "payment_date":"2026-09-10","amount":"1.69"},
                          {"ex_dividend_date":"1999-02-08","declaration_date":"None","record_date":"None",
                           "payment_date":"None","amount":"0.12"},
                          {"ex_dividend_date":"None","declaration_date":"None","record_date":"None",
                           "payment_date":"None","amount":"0.10"}]}""", MediaType.APPLICATION_JSON));

        List<Dividend> dividends = client.dividends("IBM");

        assertThat(dividends).hasSize(2);
        assertThat(dividends.get(0).paymentDate()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(dividends.get(0).amount()).isEqualByComparingTo("1.69");
        assertThat(dividends.get(1).paymentDate()).isNull();
        assertThat(client.enabled()).isTrue();
    }

    @Test
    void failsOnTheLimitMessage() {
        server.expect(requestTo("https://av.test/query?function=DIVIDENDS&symbol=IBM&apikey=key"))
                .andRespond(withSuccess("""
                        {"Information":"Our standard API rate limit is 25 requests per day."}""", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.dividends("IBM")).isInstanceOf(RestClientException.class);
        assertThat(new AlphaVantageClient(RestClient.builder(), "https://av.test", "").enabled())
                .isFalse();
    }

    @Test
    void readsTheSectorOfACompanyFromItsIndustry() {
        server.expect(requestTo("https://av.test/query?function=OVERVIEW&symbol=AAPL&apikey=key"))
                .andRespond(withSuccess("""
                        {"Symbol":"AAPL","AssetType":"Common Stock","Sector":"TECHNOLOGY",
                         "Industry":"ELECTRONIC COMPUTERS"}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://av.test/query?function=OVERVIEW&symbol=NOPE&apikey=key"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(client.sectors("AAPL", false))
                .containsExactly(new SectorWeight(Sector.INFORMATION_TECHNOLOGY, java.math.BigDecimal.ONE));
        assertThat(client.sectors("NOPE", false)).isEmpty();
    }

    @Test
    void readsTheSectorWeightsOfAFund() {
        server.expect(requestTo("https://av.test/query?function=ETF_PROFILE&symbol=SCHD&apikey=key"))
                .andRespond(withSuccess("""
                        {"net_assets":"70000000000","sectors":[
                          {"sector":"ENERGY","weight":"0.205"},
                          {"sector":"CONSUMER STAPLES","weight":"0.188"},
                          {"sector":"HEALTH CARE","weight":"0.157"},
                          {"sector":"SOMETHING NEW","weight":"0.010"}],
                         "holdings":[]}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://av.test/query?function=ETF_PROFILE&symbol=SPY&apikey=key"))
                .andRespond(withSuccess("""
                        {"Note":"Thank you for using Alpha Vantage! Our standard API call frequency is 25 a day."}""", MediaType.APPLICATION_JSON));

        List<SectorWeight> weights = client.sectors("SCHD", true);

        assertThat(weights)
                .extracting(SectorWeight::sector)
                .containsExactlyInAnyOrder(Sector.ENERGY, Sector.CONSUMER_STAPLES, Sector.HEALTH_CARE);
        assertThat(weights)
                .filteredOn(weight -> weight.sector() == Sector.ENERGY)
                .first()
                .satisfies(weight -> assertThat(weight.weight()).isEqualByComparingTo("0.205"));
        assertThatThrownBy(() -> client.sectors("SPY", true)).isInstanceOf(RestClientException.class);
    }
}
