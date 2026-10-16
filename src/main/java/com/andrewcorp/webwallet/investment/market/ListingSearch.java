package com.andrewcorp.webwallet.investment.market;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.common.util.AttemptCounter;
import com.andrewcorp.webwallet.common.web.ExternalCalls;
import com.andrewcorp.webwallet.investment.market.MarketDataProvider.Listing;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Searches of the market data provider on behalf of users. The quota of the provider is shared by everyone (and by
 * the price updates), so results are cached for an hour and a user may make {@value #SEARCHES_PER_HOUR} searches an
 * hour that reach the provider. Listings the database could not hold (no ISO currency, too long a symbol) are left
 * out, and long names are shortened.
 */
@Component
public class ListingSearch {

    static final int SEARCHES_PER_HOUR = 30;

    private static final int MAX_SYMBOL = 30;
    private static final int MAX_MIC = 10;
    private static final int MAX_NAME = 200;
    private static final int MAX_EXCHANGE = 50;
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private final MarketDataProvider provider;
    private final AttemptCounter searches = new AttemptCounter(SEARCHES_PER_HOUR, Duration.ofHours(1));
    private final Cache<String, List<Listing>> results = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(1))
            .maximumSize(2_000)
            .build();

    public ListingSearch(MarketDataProvider provider) {
        this.provider = provider;
    }

    public boolean enabled() {
        return provider.enabled();
    }

    /**
     * The listings matching the query.
     *
     * @param userId the user who asked, limited; null for the application itself
     * @throws ApiException {@code TOO_MANY_REQUESTS} over the limit of the user
     */
    public List<Listing> search(Long userId, String query) {
        String key = query.trim().toLowerCase(Locale.ROOT);
        List<Listing> cached = results.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        if (userId != null) {
            String user = userId.toString();
            if (searches.isExceeded(user)) {
                throw new ApiException(ErrorCode.TOO_MANY_REQUESTS);
            }
            searches.record(user);
        }
        List<Listing> listings = ExternalCalls.call(() -> provider.search(query.trim())).stream()
                .map(ListingSearch::sanitized)
                .filter(Objects::nonNull)
                .toList();
        results.put(key, listings);
        return listings;
    }

    /** Forgets the limits and the cached results, for tests. */
    public void reset() {
        searches.resetAll();
        results.invalidateAll();
    }

    /** The listing as the database can hold it, or null when it cannot be one. */
    static Listing sanitized(Listing listing) {
        if (listing.symbol() == null
                || listing.symbol().isBlank()
                || listing.symbol().length() > MAX_SYMBOL
                || (listing.micCode() != null && listing.micCode().length() > MAX_MIC)
                || listing.currency() == null
                || !CURRENCY.matcher(listing.currency()).matches()) {
            return null;
        }
        String name = listing.name() != null && !listing.name().isBlank() ? listing.name() : listing.symbol();
        return new Listing(
                listing.symbol(),
                cut(name, MAX_NAME),
                listing.exchange() != null ? cut(listing.exchange(), MAX_EXCHANGE) : null,
                listing.micCode() != null && listing.micCode().isBlank() ? null : listing.micCode(),
                listing.country(),
                listing.currency(),
                listing.type());
    }

    private static String cut(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
