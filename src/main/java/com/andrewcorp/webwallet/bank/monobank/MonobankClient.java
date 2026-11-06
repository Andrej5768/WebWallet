package com.andrewcorp.webwallet.bank.monobank;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.common.web.ExternalCalls;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Client of the Monobank personal API (https://api.monobank.ua/docs/). Amounts are in minor units. The API allows
 * one request per minute per token; callers are responsible for the pace.
 *
 * <p>A user is reached either with the personal token they copied from the bank, or, when WebWallet is registered as
 * a service provider, with the id of the access request they approved in the bank app: those requests carry the key
 * id of the service and a signature instead of the token (the {@code AsProvider} methods).
 */
@Component
public class MonobankClient {

    /** The statement API returns at most this many items; older ones need another request. */
    public static final int MAX_STATEMENT_ITEMS = 500;

    private static final String CLIENT_INFO = "/personal/client-info";
    private static final String STATEMENT = "/personal/statement/{account}/{from}/{to}";
    private static final String AUTH_REQUEST = "/personal/auth/request";
    private static final String PROVIDER_WEBHOOK = "/personal/corp/webhook";

    private final RestClient restClient;
    private final MonobankSigner signer;
    private final Clock clock;

    public MonobankClient(
            RestClient.Builder builder,
            @Value("${app.monobank.base-url:https://api.monobank.ua}") String baseUrl,
            MonobankSigner signer,
            Clock clock) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.signer = signer;
        this.clock = clock;
    }

    public ClientInfo clientInfo(String token) {
        return ExternalCalls.call(() -> restClient
                .get()
                .uri(CLIENT_INFO)
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
                .uri(STATEMENT, accountId, from.getEpochSecond(), to.getEpochSecond())
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

    public ClientInfo clientInfoAsProvider(String requestId) {
        return ExternalCalls.call(() -> restClient
                .get()
                .uri(CLIENT_INFO)
                .headers(provider(requestId, CLIENT_INFO))
                .retrieve()
                .body(ClientInfo.class));
    }

    public List<StatementItem> statementAsProvider(String requestId, String accountId, Instant from, Instant to) {
        // The signature covers the path as it is sent
        String path = UriComponentsBuilder.fromPath(STATEMENT)
                .buildAndExpand(accountId, from.getEpochSecond(), to.getEpochSecond())
                .encode()
                .toUriString();
        return ExternalCalls.call(() -> restClient
                .get()
                .uri(path)
                .headers(provider(requestId, path))
                .retrieve()
                .body(new ParameterizedTypeReference<List<StatementItem>>() {}));
    }

    /**
     * Asks for access to the data of a user. The user approves it in the bank app by opening the accept URL (or
     * scanning it as a QR code) within 24 hours; the bank then calls the callback, if given, with the request id in
     * the {@code X-Request-Id} header. The request id then stands for the user in every later request.
     */
    public AccessRequest requestAccess(String callbackUrl) {
        return ExternalCalls.callWithServerKey(() -> restClient
                .post()
                .uri(AUTH_REQUEST)
                .headers(provider(null, AUTH_REQUEST))
                .headers(headers -> {
                    if (callbackUrl != null) {
                        headers.set("X-Callback", callbackUrl);
                    }
                })
                .retrieve()
                .body(AccessRequest.class));
    }

    /** Whether the user approved the access request; false while it waits, and when it was declined or expired. */
    public boolean accessGranted(String requestId) {
        try {
            restClient
                    .get()
                    .uri(AUTH_REQUEST)
                    .headers(provider(requestId, AUTH_REQUEST))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.TooManyRequests e) {
            throw new ApiException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE);
        } catch (HttpClientErrorException e) {
            return false;
        } catch (RestClientException e) {
            throw new ApiException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Sets the one webhook of the service: the bank posts there the new operations of every user who gave access,
     * after a test call to the URL.
     */
    public void setProviderWebhook(String url) {
        // The bank asks for a request id here as well, though no user is involved; a unique value does
        String requestId = UUID.randomUUID().toString();
        ExternalCalls.callWithServerKey(() -> restClient
                .post()
                .uri(PROVIDER_WEBHOOK)
                .headers(provider(null, PROVIDER_WEBHOOK))
                .header("X-Request-Id", requestId)
                .body(Map.of("webHookUrl", url))
                .retrieve()
                .toBodilessEntity());
    }

    /**
     * The headers of a provider request: the key id, the time, the request id of the user when there is one, and the
     * signature of the time, that request id and the path.
     */
    private Consumer<HttpHeaders> provider(String requestId, String path) {
        String time = Long.toString(clock.instant().getEpochSecond());
        String signature = signer.sign(time + (requestId == null ? "" : requestId) + path);
        return headers -> {
            headers.set("X-Key-Id", signer.keyId());
            headers.set("X-Time", time);
            headers.set("X-Sign", signature);
            if (requestId != null) {
                headers.set("X-Request-Id", requestId);
            }
        };
    }

    /** @param acceptUrl shown to the user as a link or a QR code; opens the bank app to approve the access */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AccessRequest(String tokenRequestId, String acceptUrl) {}

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
