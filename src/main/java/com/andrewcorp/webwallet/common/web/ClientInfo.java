package com.andrewcorp.webwallet.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;

/**
 * Which client made the request, from the {@code X-WebWallet-Client} and {@code X-App-Version} headers the native
 * apps send on every request; the web client sends none and counts as {@link Kind#WEB}.
 *
 * @param kind the client, {@link Kind#WEB} when the header is absent or unknown
 * @param appVersion the {@code versionCode} of the app, null for the web and when the header is not a positive number
 */
public record ClientInfo(Kind kind, Integer appVersion) {

    public static final String CLIENT_HEADER = "X-WebWallet-Client";
    public static final String VERSION_HEADER = "X-App-Version";
    public static final String REQUEST_ATTRIBUTE = ClientInfo.class.getName();
    public static final ClientInfo WEB = new ClientInfo(Kind.WEB, null);

    private static final int MAX_VERSION_LENGTH = 20;

    public enum Kind {
        WEB,
        ANDROID,
        IOS
    }

    /** True for the mobile apps, which keep their tokens themselves instead of in cookies. */
    public boolean nativeApp() {
        return kind != Kind.WEB;
    }

    /** The lower-case name of the client for the logs: {@code web}, {@code android}, {@code ios}. */
    public String logName() {
        return kind.name().toLowerCase(Locale.ROOT);
    }

    /** Parses the headers of the request; never throws, anything unexpected means the web client. */
    public static ClientInfo of(HttpServletRequest request) {
        Kind kind = parseKind(request.getHeader(CLIENT_HEADER));
        return kind == Kind.WEB ? WEB : new ClientInfo(kind, parseVersion(request.getHeader(VERSION_HEADER)));
    }

    /**
     * The client of the request as determined by the filter at its start, or parsed now when the request did not go
     * through the filter.
     */
    public static ClientInfo current(HttpServletRequest request) {
        return request.getAttribute(REQUEST_ATTRIBUTE) instanceof ClientInfo client ? client : of(request);
    }

    private static Kind parseKind(String header) {
        if (header == null) {
            return Kind.WEB;
        }
        String value = header.trim();
        for (Kind kind : Kind.values()) {
            if (kind != Kind.WEB && kind.name().equalsIgnoreCase(value)) {
                return kind;
            }
        }
        return Kind.WEB;
    }

    private static Integer parseVersion(String header) {
        if (header == null) {
            return null;
        }
        String value = header.trim();
        if (value.isEmpty() || value.length() > MAX_VERSION_LENGTH) {
            return null;
        }
        try {
            int version = Integer.parseInt(value);
            return version > 0 ? version : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
