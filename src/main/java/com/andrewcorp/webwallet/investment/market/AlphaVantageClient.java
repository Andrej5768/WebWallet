package com.andrewcorp.webwallet.investment.market;

import com.andrewcorp.webwallet.investment.Sector;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Dividends and sectors from Alpha Vantage (https://www.alphavantage.co/documentation/), free with an API key and
 * about 25 requests a day: {@code DIVIDENDS}, {@code OVERVIEW} of a company (its SEC sector and industry) and
 * {@code ETF_PROFILE} of a fund (its sector weights). A limit or a bad key comes as a 200 response with an
 * {@code Information} or {@code Note} text instead of data; unknown values are {@code "None"}.
 */
@Component
@ConditionalOnProperty(
        name = "app.market-data.dividends-provider",
        havingValue = "alpha-vantage",
        matchIfMissing = true)
public class AlphaVantageClient implements DividendProvider, SectorProvider {

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

    @Override
    public List<SectorWeight> sectors(String symbol, boolean fund) {
        if (fund) {
            FundProfile profile = restClient
                    .get()
                    .uri("/query?function=ETF_PROFILE&symbol={symbol}&apikey={key}", symbol, apiKey)
                    .retrieve()
                    .body(FundProfile.class);
            if (profile == null || profile.sectors() == null) {
                limited(profile != null ? firstOf(profile.information(), profile.note()) : null);
                return List.of();
            }
            Map<Sector, BigDecimal> weights = new EnumMap<>(Sector.class);
            for (FundSector item : profile.sectors()) {
                Sector sector = Sector.ofName(item.sector());
                BigDecimal weight = amount(item.weight());
                if (sector != null && weight != null) {
                    weights.merge(sector, weight.min(BigDecimal.ONE), BigDecimal::add);
                }
            }
            return weights.entrySet().stream()
                    .map(entry ->
                            new SectorWeight(entry.getKey(), entry.getValue().min(BigDecimal.ONE)))
                    .toList();
        }
        Overview overview = restClient
                .get()
                .uri("/query?function=OVERVIEW&symbol={symbol}&apikey={key}", symbol, apiKey)
                .retrieve()
                .body(Overview.class);
        if (overview == null || overview.symbol() == null) {
            limited(overview != null ? firstOf(overview.information(), overview.note()) : null);
            return List.of();
        }
        Sector sector = Sector.ofCompany(overview.sector(), overview.industry());
        return sector != null ? List.of(new SectorWeight(sector, BigDecimal.ONE)) : List.of();
    }

    private static String firstOf(String first, String second) {
        return first != null ? first : second;
    }

    /** A limit or a bad key, which must not count as an unknown symbol. */
    private static void limited(String information) {
        if (information != null) {
            throw new RestClientException("Alpha Vantage returned no data: " + information);
        }
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
    record Overview(
            @JsonProperty("Symbol") String symbol,
            @JsonProperty("Sector") String sector,
            @JsonProperty("Industry") String industry,
            @JsonProperty("Information") String information,
            @JsonProperty("Note") String note) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FundProfile(
            List<FundSector> sectors,
            @JsonProperty("Information") String information,
            @JsonProperty("Note") String note) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FundSector(String sector, String weight) {}

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
