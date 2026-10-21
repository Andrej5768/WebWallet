package com.andrewcorp.webwallet.auth;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.common.util.AttemptCounter;
import com.andrewcorp.webwallet.config.AppProperties;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

/**
 * Slows down password guessing and email flooding: counts failed logins per client IP, per account from one IP and
 * per account from all IPs, and requests that send an email per client IP and per address. Emails are expected to be
 * normalized. A few wrong passwords from elsewhere do not lock the owner out: an account is blocked for every IP only
 * after many more failures than it is for the IP that made them.
 */
@Service
public class AuthRateLimiter {

    /** How many times the failures of one IP block an account for all IPs, and two-factor codes for a day. */
    private static final int ACCOUNT_FACTOR = 3;

    private final AttemptCounter failedLoginsByIp;
    private final AttemptCounter failedLoginsByAccount;
    private final AttemptCounter failedLoginsByAccountAndIp;
    private final AttemptCounter emailRequestsByIp;
    private final AttemptCounter emailRequestsByAddress;
    /** Wrong current passwords when changing the password, e.g. with a stolen access token. */
    private final AttemptCounter failedPasswordChecksByUser;
    /** Wrong two-factor codes, after the password or with a stolen access token. */
    private final AttemptCounter failedTwoFactorCodesByUser;
    /** The same over a day, so that codes cannot be guessed slowly, a few every window, for months. */
    private final AttemptCounter failedTwoFactorCodesByUserDaily;

    public AuthRateLimiter(AppProperties properties) {
        AppProperties.Auth auth = properties.auth();
        this.failedLoginsByIp = new AttemptCounter(auth.maxFailedLogins(), auth.loginBlockDuration());
        this.failedLoginsByAccount =
                new AttemptCounter(auth.maxFailedLogins() * ACCOUNT_FACTOR, auth.accountBlockDuration());
        this.failedLoginsByAccountAndIp = new AttemptCounter(auth.maxFailedLogins(), auth.accountBlockDuration());
        this.emailRequestsByIp = new AttemptCounter(auth.maxEmailRequestsPerIp(), auth.emailRequestWindow());
        this.failedPasswordChecksByUser = new AttemptCounter(auth.maxFailedLogins(), auth.accountBlockDuration());
        this.failedTwoFactorCodesByUser = new AttemptCounter(auth.maxFailedLogins(), auth.accountBlockDuration());
        this.failedTwoFactorCodesByUserDaily =
                new AttemptCounter(auth.maxFailedLogins() * ACCOUNT_FACTOR, Duration.ofDays(1));
        this.emailRequestsByAddress = new AttemptCounter(auth.maxEmailRequestsPerAddress(), auth.emailRequestWindow());
    }

    public void checkLogin(String ip, String email) {
        if (failedLoginsByIp.isExceeded(clientKey(ip))
                || failedLoginsByAccountAndIp.isExceeded(pair(ip, email))
                || failedLoginsByAccount.isExceeded(email)) {
            throw new ApiException(ErrorCode.TOO_MANY_ATTEMPTS);
        }
    }

    public void loginFailed(String ip, String email) {
        failedLoginsByIp.record(clientKey(ip));
        failedLoginsByAccountAndIp.record(pair(ip, email));
        failedLoginsByAccount.record(email);
    }

    /** Forgets the failures of the account only: signing in to one account must not unblock the IP for others. */
    public void loginSucceeded(String ip, String email) {
        failedLoginsByAccount.reset(email);
        failedLoginsByAccountAndIp.reset(pair(ip, email));
    }

    private static String pair(String ip, String email) {
        return clientKey(ip) + "|" + email;
    }

    public void checkPasswordChange(Long userId) {
        if (failedPasswordChecksByUser.isExceeded(userId.toString())) {
            throw new ApiException(ErrorCode.TOO_MANY_ATTEMPTS);
        }
    }

    public void passwordCheckFailed(Long userId) {
        failedPasswordChecksByUser.record(userId.toString());
    }

    public void checkTwoFactor(Long userId) {
        if (failedTwoFactorCodesByUser.isExceeded(userId.toString())
                || failedTwoFactorCodesByUserDaily.isExceeded(userId.toString())) {
            throw new ApiException(ErrorCode.TOO_MANY_ATTEMPTS);
        }
    }

    public void twoFactorFailed(Long userId) {
        failedTwoFactorCodesByUser.record(userId.toString());
        failedTwoFactorCodesByUserDaily.record(userId.toString());
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
    public static String clientKey(String ip) {
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
        failedLoginsByAccountAndIp.resetAll();
        emailRequestsByIp.resetAll();
        emailRequestsByAddress.resetAll();
        failedPasswordChecksByUser.resetAll();
        failedTwoFactorCodesByUser.resetAll();
        failedTwoFactorCodesByUserDaily.resetAll();
    }
}
