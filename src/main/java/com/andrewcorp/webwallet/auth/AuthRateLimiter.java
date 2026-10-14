package com.andrewcorp.webwallet.auth;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.config.AppProperties;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HexFormat;
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
    /** Wrong current passwords when changing the password, e.g. with a stolen access token. */
    private final AttemptCounter failedPasswordChecksByUser;

    public AuthRateLimiter(AppProperties properties) {
        AppProperties.Auth auth = properties.auth();
        this.failedLoginsByIp = new AttemptCounter(auth.maxFailedLogins(), auth.loginBlockDuration());
        this.failedLoginsByAccount = new AttemptCounter(auth.maxFailedLogins(), auth.accountBlockDuration());
        this.emailRequestsByIp = new AttemptCounter(auth.maxEmailRequestsPerIp(), auth.emailRequestWindow());
        this.failedPasswordChecksByUser = new AttemptCounter(auth.maxFailedLogins(), auth.accountBlockDuration());
        this.emailRequestsByAddress = new AttemptCounter(auth.maxEmailRequestsPerAddress(), auth.emailRequestWindow());
    }

    public void checkLogin(String ip, String email) {
        if (failedLoginsByIp.isExceeded(clientKey(ip)) || failedLoginsByAccount.isExceeded(email)) {
            throw new ApiException(ErrorCode.TOO_MANY_ATTEMPTS);
        }
    }

    public void loginFailed(String ip, String email) {
        failedLoginsByIp.record(clientKey(ip));
        failedLoginsByAccount.record(email);
    }

    /** Forgets the failures of the account only: signing in to one account must not unblock the IP for others. */
    public void loginSucceeded(String email) {
        failedLoginsByAccount.reset(email);
    }

    public void checkPasswordChange(Long userId) {
        if (failedPasswordChecksByUser.isExceeded(userId.toString())) {
            throw new ApiException(ErrorCode.TOO_MANY_ATTEMPTS);
        }
    }

    public void passwordCheckFailed(Long userId) {
        failedPasswordChecksByUser.record(userId.toString());
    }

    /** Counts a request that may send an email, rejecting it when the IP or the address made too many. */
    public void emailRequested(String ip, String email) {
        String client = clientKey(ip);
        String mailbox = mailboxKey(email);
        if (emailRequestsByIp.isExceeded(client) || emailRequestsByAddress.isExceeded(mailbox)) {
            throw new ApiException(ErrorCode.TOO_MANY_REQUESTS);
        }
        emailRequestsByIp.record(client);
        emailRequestsByAddress.record(mailbox);
    }

    /**
     * An IPv6 client usually gets a whole /64 network and could change its address within it at will, so it counts
     * as one client.
     */
    static String clientKey(String ip) {
        if (ip == null || !ip.contains(":")) {
            return ip;
        }
        try {
            byte[] address = InetAddress.getByName(ip).getAddress();
            return address.length == 16 ? HexFormat.of().formatHex(address, 0, 8) + "::/64" : ip;
        } catch (UnknownHostException e) {
            return ip;
        }
    }

    /** {@code user+anything@example.com} reaches the same mailbox as {@code user@example.com}. */
    static String mailboxKey(String email) {
        int at = email.lastIndexOf('@');
        int plus = email.indexOf('+');
        return plus > 0 && plus < at ? email.substring(0, plus) + email.substring(at) : email;
    }

    /** Forgets all attempts. */
    public void reset() {
        failedLoginsByIp.resetAll();
        failedLoginsByAccount.resetAll();
        emailRequestsByIp.resetAll();
        emailRequestsByAddress.resetAll();
        failedPasswordChecksByUser.resetAll();
    }
}
