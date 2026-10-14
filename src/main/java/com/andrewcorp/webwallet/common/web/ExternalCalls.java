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
