package com.andrewcorp.webwallet.investment.market;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Client of the Twelve Data API (https://twelvedata.com/docs). The free plan allows 800 requests a day and 8 a minute;
 * an error comes as a JSON body with {@code status: "error"} and a {@code code}, e.g. 404 for an unknown symbol or 429
 * when the limit is reached.
 */
@Component
@ConditionalOnProperty(name = "app.market-data.prices-provider", havingValue = "twelve-data", matchIfMissing = true)
public class TwelveDataClient implements MarketDataProvider {

    private static final int MAX_SEARCH_RESULTS = 20;

    private final RestClient restClient;
    private final String apiKey;

    public TwelveDataClient(
            RestClient.Builder builder,
            @Value("${app.market-data.twelve-data.base-url:https://api.twelvedata.com}") String baseUrl,
            @Value("${app.market-data.twelve-data.api-key:}") String apiKey) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
    }

    @Override
    public boolean enabled() {
        return !apiKey.isBlank();
    }

    @Override
    public List<Listing> search(String query) {
        SearchResponse response = restClient
                .get()
                .uri("/symbol_search?symbol={query}&outputsize={size}&apikey={key}", query, MAX_SEARCH_RESULTS, apiKey)
                .retrieve()
                .body(SearchResponse.class);
        if (response == null || response.data() == null) {
            return List.of();
        }
        return response.data().stream()
                .map(item -> new Listing(
                        item.symbol(),
                        item.name(),
                        item.exchange(),
                        item.micCode(),
                        item.country(),
                        item.currency(),
                        item.type()))
                .toList();
    }

    /**
     * Closes as traded, not adjusted: the operations record splits and dividends themselves, and adjusted history
     * would count them twice.
     */
    @Override
    public List<DailyClose> closes(String symbol, String micCode, LocalDate from, LocalDate to) {
        SeriesResponse response = restClient
                .get()
                .uri(
                        "/time_series?symbol={symbol}&mic_code={mic}&interval=1day&start_date={from}&end_date={to}"
                                + "&order=asc&adjust=none&apikey={key}",
                        symbol,
                        micCode != null ? micCode : "",
                        from,
                        to,
                        apiKey)
                .retrieve()
                .body(SeriesResponse.class);
        if (response == null) {
            return List.of();
        }
        if ("error".equals(response.status())) {
            // No data for the period (e.g. a holiday) is not a failure
            if (response.code() != null && response.code() == 404) {
                return List.of();
            }
            throw new RestClientException("Twelve Data error " + response.code());
        }
        if (response.values() == null) {
            return List.of();
        }
        return response.values().stream()
                .map(value -> new DailyClose(LocalDate.parse(value.datetime().substring(0, 10)), value.close()))
                .sorted(Comparator.comparing(DailyClose::date))
                .toList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResponse(List<SearchItem> data, String status) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchItem(
            String symbol,
            @JsonProperty("instrument_name") String name,
            String exchange,
            @JsonProperty("mic_code") String micCode,
            String country,
            String currency,
            @JsonProperty("instrument_type") String type) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SeriesResponse(List<SeriesValue> values, String status, Integer code) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SeriesValue(String datetime, BigDecimal close) {}
}
