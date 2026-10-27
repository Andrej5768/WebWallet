package com.andrewcorp.webwallet.idempotency;

import com.andrewcorp.webwallet.common.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Gives a create request that a client sends again with the same {@code Idempotency-Key} the response of the first
 * one, so that an app retrying after a lost response does not create the transaction twice. The key is a UUID chosen
 * by the client and belongs to the signed-in user for a day; a stored response is replayed only for the very same
 * request, the same key with another request is a conflict. Only successful responses are kept, so a request that
 * failed can be corrected and sent again under its key. Runs after the security chain, which answers 401 itself.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    /** Set to {@code true} on a replayed response. */
    public static final String REPLAYED_HEADER = "Idempotency-Replayed";

    public static final Duration RETENTION = Duration.ofHours(24);

    /** Writes the problem response when the key or its reuse is rejected. */
    @FunctionalInterface
    public interface Rejection {
        void reject(HttpServletRequest request, HttpServletResponse response, ErrorCode code) throws IOException;
    }

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final PathPatternParser PARSER = new PathPatternParser();
    /** The POST endpoints that create something; the other POSTs are reads or changes of a whole state. */
    private static final List<PathPattern> CREATE_PATHS = Stream.of(
                    "/api/v1/accounts",
                    "/api/v1/transactions",
                    "/api/v1/transactions/batch",
                    "/api/v1/investments/operations",
                    "/api/v1/goals",
                    "/api/v1/goals/{id}/contributions",
                    "/api/v1/recurring",
                    "/api/v1/categories",
                    "/api/v1/tags",
                    "/api/v1/payees",
                    "/api/v1/categorization-rules",
                    "/api/v1/imports/previews/{id}/confirm",
                    "/api/v1/imports/freedom/previews/{id}/confirm",
                    "/api/v1/budget/{month}/move")
            .map(PARSER::parse)
            .toList();

    private final IdempotencyKeyRepository keys;
    private final Clock clock;
    private final Rejection rejection;

    public IdempotencyFilter(IdempotencyKeyRepository keys, Clock clock, Rejection rejection) {
        this.keys = keys;
        this.clock = clock;
        this.rejection = rejection;
    }

    /** Whether the request creates something and may therefore carry a key; the OpenAPI spec documents it there. */
    public static boolean isCreateRequest(String method, String path) {
        if (!HttpMethod.POST.matches(method)) {
            return false;
        }
        PathContainer container = PathContainer.parsePath(path);
        return CREATE_PATHS.stream().anyMatch(pattern -> pattern.matches(container));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getHeader(HEADER) == null || !isCreateRequest(request.getMethod(), request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Long userId = currentUserId();
        if (userId == null) {
            chain.doFilter(request, response);
            return;
        }
        UUID key = parseKey(request.getHeader(HEADER));
        if (key == null) {
            rejection.reject(request, response, ErrorCode.INVALID_IDEMPOTENCY_KEY);
            return;
        }
        BufferedBodyRequest buffered = new BufferedBodyRequest(request);
        String hash = hash(buffered);
        Instant now = clock.instant();
        Optional<IdempotencyKey> stored = keys.findByUserIdAndRequestKey(userId, key);
        if (stored.isPresent()) {
            IdempotencyKey existing = stored.get();
            if (existing.getCreatedAt().isAfter(now.minus(RETENTION))) {
                if (!existing.getRequestHash().equals(hash)) {
                    rejection.reject(request, response, ErrorCode.IDEMPOTENCY_MISMATCH);
                    return;
                }
                replay(existing, response);
                return;
            }
            // The key outlived its day and is free again
            keys.delete(existing);
        }
        ContentCachingResponseWrapper cached = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(buffered, cached);
            if (HttpStatus.valueOf(cached.getStatus()).is2xxSuccessful()) {
                remember(userId, key, hash, cached, now);
            }
        } finally {
            cached.copyBodyToResponse();
        }
    }

    private void remember(Long userId, UUID key, String hash, ContentCachingResponseWrapper response, Instant now) {
        String body = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        try {
            keys.save(
                    new IdempotencyKey(userId, key, hash, response.getStatus(), response.getContentType(), body, now));
        } catch (DataIntegrityViolationException e) {
            // The same key arrived twice at once; the response stored first is the one replayed
            log.debug("Idempotency key of user {} was stored by a parallel request", userId);
        }
    }

    private static void replay(IdempotencyKey stored, HttpServletResponse response) throws IOException {
        response.setStatus(stored.getStatus());
        if (stored.getContentType() != null) {
            response.setContentType(stored.getContentType());
        }
        response.setHeader(REPLAYED_HEADER, "true");
        byte[] body = stored.getResponseBody().getBytes(StandardCharsets.UTF_8);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    private static Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof Jwt jwt
                ? Long.valueOf(jwt.getSubject())
                : null;
    }

    private static UUID parseKey(String header) {
        try {
            return UUID.fromString(header.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The request as the client meant it: the path, the query and the body. */
    private static String hash(BufferedBodyRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(request.getRequestURI().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            if (request.getQueryString() != null) {
                digest.update(request.getQueryString().getBytes(StandardCharsets.UTF_8));
            }
            digest.update((byte) '\n');
            digest.update(request.body());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }
}
