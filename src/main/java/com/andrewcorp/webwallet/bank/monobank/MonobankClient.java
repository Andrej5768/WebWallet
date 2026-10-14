package com.andrewcorp.webwallet.bank.monobank;

import com.andrewcorp.webwallet.common.web.ExternalCalls;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Client of the Monobank personal API (https://api.monobank.ua/docs/). Amounts are in minor units. The API allows
 * one request per minute per token; callers are responsible for the pace.
 */
@Component
public class MonobankClient {

    /** The statement API returns at most this many items; older ones need another request. */
    public static final int MAX_STATEMENT_ITEMS = 500;

    private final RestClient restClient;

    public MonobankClient(
            RestClient.Builder builder, @Value("${app.monobank.base-url:https://api.monobank.ua}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    public ClientInfo clientInfo(String token) {
        return ExternalCalls.call(() -> restClient
                .get()
                .uri("/personal/client-info")
                .header("X-Token", token)
                .retrieve()
                .body(ClientInfo.class));
    }

    /**
     * Operations of the account between the two moments, newest first. The period may not exceed 31 days and 1 hour.
     */
    public List<StatementItem> statement(String token, String accountId, Instant from, Instant to) {
        return ExternalCalls.call(() -> restClient
                .get()
                .uri("/personal/statement/{account}/{from}/{to}", accountId, from.getEpochSecond(), to.getEpochSecond())
                .header("X-Token", token)
                .retrieve()
                .body(new ParameterizedTypeReference<List<StatementItem>>() {}));
    }

    /** Monobank checks the URL with a GET request and then posts every new operation to it. */
    public void setWebhook(String token, String url) {
        ExternalCalls.call(() -> restClient
                .post()
                .uri("/personal/webhook")
                .header("X-Token", token)
                .body(Map.of("webHookUrl", url))
                .retrieve()
                .toBodilessEntity());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClientInfo(String clientId, String name, List<BankAccount> accounts, List<Jar> jars) {}

    /**
     * @param currencyCode ISO 4217 numeric code
     * @param maskedPan    masked card numbers, e.g. {@code 537541******1234}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BankAccount(
            String id,
            long balance,
            long creditLimit,
            String type,
            int currencyCode,
            List<String> maskedPan,
            String iban) {}

    /** A savings jar ("банка"). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Jar(String id, String title, int currencyCode, long balance) {}

    /**
     * @param time            unix time in seconds
     * @param amount          signed amount in the account currency, minor units
     * @param operationAmount signed amount in the operation currency, minor units
     * @param currencyCode    ISO 4217 numeric code of the operation currency
     * @param balance         account balance after the operation, minor units
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StatementItem(
            String id,
            long time,
            String description,
            Integer mcc,
            boolean hold,
            long amount,
            long operationAmount,
            int currencyCode,
            long balance,
            String comment,
            String counterName) {}
}
