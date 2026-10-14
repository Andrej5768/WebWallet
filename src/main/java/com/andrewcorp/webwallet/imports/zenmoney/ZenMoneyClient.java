package com.andrewcorp.webwallet.imports.zenmoney;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import java.time.Clock;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Downloads the whole account from the Zen Money sync API. {@code api.zenmoney.ru} is blocked by DNS in Ukraine,
 * {@code api.zenmoney.app} is the same API.
 */
@Component
public class ZenMoneyClient {

    private final RestClient restClient;
    private final Clock clock;

    public ZenMoneyClient(
            RestClient.Builder builder,
            @Value("${app.zenmoney.base-url:https://api.zenmoney.app}") String baseUrl,
            Clock clock) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.clock = clock;
    }

    /** Full export: a sync request with {@code serverTimestamp = 0} returns everything. */
    public ZenMoneyDump fetchAll(String token) {
        try {
            return restClient
                    .post()
                    .uri("/v8/diff/")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("currentClientTimestamp", clock.instant().getEpochSecond(), "serverTimestamp", 0))
                    .retrieve()
                    .body(ZenMoneyDump.class);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED || e.getStatusCode() == HttpStatus.FORBIDDEN) {
                throw new ApiException(ErrorCode.INVALID_EXTERNAL_TOKEN);
            }
            throw new ApiException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE);
        } catch (RestClientException e) {
            throw new ApiException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE);
        }
    }
}
