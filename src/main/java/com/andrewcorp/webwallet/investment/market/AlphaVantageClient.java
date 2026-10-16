package com.andrewcorp.webwallet.investment.market;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Dividends from Alpha Vantage (https://www.alphavantage.co/documentation/#dividends), free with an API key and about
 * 25 requests a day. A limit or a bad key comes as a 200 response with an {@code Information} or {@code Note} text
 * instead of data; unknown dates are {@code "None"}.
 */
@Component
@ConditionalOnProperty(
        name = "app.market-data.dividends-provider",
        havingValue = "alpha-vantage",
        matchIfMissing = true)
public class AlphaVantageClient implements DividendProvider {

    private final RestClient restClient;
    private final String apiKey;

    public AlphaVantageClient(
            RestClient.Builder builder,
            @Value("${app.market-data.alpha-vantage.base-url:https://www.alphavantage.co}") String baseUrl,
            @Value("${app.market-data.alpha-vantage.api-key:}") String apiKey) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
    }

    @Override
    public boolean enabled() {
        return !apiKey.isBlank();
    }

    @Override
    public List<Dividend> dividends(String symbol) {
        Response response = restClient
                .get()
                .uri("/query?function=DIVIDENDS&symbol={symbol}&apikey={key}", symbol, apiKey)
                .retrieve()
                .body(Response.class);
        if (response == null || response.data() == null) {
            throw new RestClientException("Alpha Vantage returned no dividends: "
                    + (response != null ? Objects.requireNonNullElse(response.information(), response.note()) : null));
        }
        return response.data().stream()
                .filter(item -> date(item.exDate()) != null && amount(item.amount()) != null)
                .map(item -> new Dividend(
                        date(item.exDate()),
                        date(item.paymentDate()),
                        date(item.declarationDate()),
                        date(item.recordDate()),
                        amount(item.amount())))
                .toList();
    }

    private static LocalDate date(String value) {
        return value == null || value.isBlank() || value.equals("None") ? null : LocalDate.parse(value);
    }

    private static BigDecimal amount(String value) {
        if (value == null || value.isBlank() || value.equals("None")) {
            return null;
        }
        BigDecimal amount = new BigDecimal(value);
        return amount.signum() > 0 ? amount : null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(
            List<Item> data,
            @JsonProperty("Information") String information,
            @JsonProperty("Note") String note) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Item(
            @JsonProperty("ex_dividend_date") String exDate,
            @JsonProperty("payment_date") String paymentDate,
            @JsonProperty("declaration_date") String declarationDate,
            @JsonProperty("record_date") String recordDate,
            String amount) {}
}
