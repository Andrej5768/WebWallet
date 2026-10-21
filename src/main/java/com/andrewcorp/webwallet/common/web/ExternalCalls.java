package com.andrewcorp.webwallet.common.web;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/** Calls of external services (banks, Zen Money) with the user's token. */
public final class ExternalCalls {

    private ExternalCalls() {}

    /**
     * Runs a request of a service with the key of the server rather than a token of the user, e.g. market data: any
     * failure is {@link ErrorCode#EXTERNAL_SERVICE_UNAVAILABLE}, never the token error that asks the user for a new one.
     */
    public static <T> T callWithServerKey(Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientException e) {
            throw new ApiException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Runs the request; a rejected token becomes {@link ErrorCode#INVALID_EXTERNAL_TOKEN}, any other failure
     * {@link ErrorCode#EXTERNAL_SERVICE_UNAVAILABLE}.
     */
    public static <T> T call(Supplier<T> request) {
        try {
            return request.get();
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
