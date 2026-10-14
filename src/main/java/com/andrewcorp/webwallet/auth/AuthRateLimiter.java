package com.andrewcorp.webwallet.auth;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.config.AppProperties;
import org.springframework.stereotype.Service;

/**
 * Slows down password guessing and email flooding: counts failed logins per client IP and per account, and
 * requests that send an email per client IP and per address. Emails are expected to be normalized.
 */
@Service
public class AuthRateLimiter {

    private final AttemptCounter failedLoginsByIp;
    private final AttemptCounter failedLoginsByAccount;
    private final AttemptCounter emailRequestsByIp;
    private final AttemptCounter emailRequestsByAddress;

    public AuthRateLimiter(AppProperties properties) {
        AppProperties.Auth auth = properties.auth();
        this.failedLoginsByIp = new AttemptCounter(auth.maxFailedLogins(), auth.loginBlockDuration());
        this.failedLoginsByAccount = new AttemptCounter(auth.maxFailedLogins(), auth.accountBlockDuration());
        this.emailRequestsByIp = new AttemptCounter(auth.maxEmailRequestsPerIp(), auth.emailRequestWindow());
        this.emailRequestsByAddress = new AttemptCounter(auth.maxEmailRequestsPerAddress(), auth.emailRequestWindow());
    }

    public void checkLogin(String ip, String email) {
        if (failedLoginsByIp.isExceeded(ip) || failedLoginsByAccount.isExceeded(email)) {
            throw new ApiException(ErrorCode.TOO_MANY_ATTEMPTS);
        }
    }

    public void loginFailed(String ip, String email) {
        failedLoginsByIp.record(ip);
        failedLoginsByAccount.record(email);
    }

    /** Forgets the failures of the account only: signing in to one account must not unblock the IP for others. */
    public void loginSucceeded(String email) {
        failedLoginsByAccount.reset(email);
    }

    /** Counts a request that may send an email, rejecting it when the IP or the address made too many. */
    public void emailRequested(String ip, String email) {
        if (emailRequestsByIp.isExceeded(ip) || emailRequestsByAddress.isExceeded(email)) {
            throw new ApiException(ErrorCode.TOO_MANY_REQUESTS);
        }
        emailRequestsByIp.record(ip);
        emailRequestsByAddress.record(email);
    }

    /** Forgets all attempts. */
    public void reset() {
        failedLoginsByIp.resetAll();
        failedLoginsByAccount.resetAll();
        emailRequestsByIp.resetAll();
        emailRequestsByAddress.resetAll();
    }
}
