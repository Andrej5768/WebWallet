package com.andrewcorp.webwallet.currency;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Client of the public National Bank of Ukraine exchange rate API.
 */
@Component
public class NbuClient {

    private static final DateTimeFormatter REQUEST_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter RESPONSE_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final RestClient restClient;

    public NbuClient(RestClient.Builder builder, @Value("${app.nbu.base-url:https://bank.gov.ua}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    /** Rates of all currencies published for the date. */
    public List<ExchangeRate> ratesOn(LocalDate date) {
        List<NbuRate> rates = restClient
                .get()
                .uri("/NBUStatService/v1/statdirectory/exchange?date={date}&json", REQUEST_DATE.format(date))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return rates == null
                ? List.of()
                : rates.stream()
                        .map(rate -> new ExchangeRate(
                                rate.currency(), LocalDate.parse(rate.date(), RESPONSE_DATE), rate.rate()))
                        .toList();
    }

    /** Daily rates of one currency for a period, both ends inclusive. */
    public List<ExchangeRate> history(String currency, LocalDate from, LocalDate to) {
        List<NbuRate> rates = restClient
                .get()
                .uri(
                        "/NBU_Exchange/exchange_site?start={from}&end={to}&valcode={currency}&sort=exchangedate&order=asc&json",
                        REQUEST_DATE.format(from),
                        REQUEST_DATE.format(to),
                        currency.toLowerCase(Locale.ROOT))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return rates == null
                ? List.of()
                : rates.stream()
                        .map(rate -> new ExchangeRate(
                                rate.currency(),
                                LocalDate.parse(rate.date(), RESPONSE_DATE),
                                rate.ratePerUnit() != null ? rate.ratePerUnit() : rate.rate()))
                        .toList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NbuRate(
            @JsonProperty("cc") String currency,
            @JsonProperty("exchangedate") String date,
            @JsonProperty("rate") BigDecimal rate,
            @JsonProperty("rate_per_unit") BigDecimal ratePerUnit) {}
}
