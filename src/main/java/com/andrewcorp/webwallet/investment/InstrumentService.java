package com.andrewcorp.webwallet.investment;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.common.web.Periods;
import com.andrewcorp.webwallet.investment.InvestmentDtos.InstrumentResponse;
import com.andrewcorp.webwallet.investment.InvestmentDtos.MarketInstrumentRequest;
import com.andrewcorp.webwallet.investment.InvestmentDtos.OwnInstrumentRequest;
import com.andrewcorp.webwallet.investment.InvestmentDtos.PricePoint;
import com.andrewcorp.webwallet.investment.InvestmentDtos.SearchResponse;
import com.andrewcorp.webwallet.investment.InvestmentDtos.SearchResult;
import com.andrewcorp.webwallet.investment.market.ListingSearch;
import com.andrewcorp.webwallet.investment.market.MarketDataProvider.Listing;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Instruments: market ones are found at the market data provider and shared by all users, own ones are entered by
 * hand.
 */
@Service
public class InstrumentService {

    /** Shorter queries find only own and known instruments: the provider would return noise for them. */
    static final int MIN_PROVIDER_QUERY = 2;

    private final InstrumentRepository instruments;
    private final InstrumentPriceRepository prices;
    private final InvestmentOperationRepository operations;
    private final ListingSearch listings;
    private final Clock clock;

    /** Market instruments each user found by a search or added, which they may read for a day. */
    private final Cache<Long, Set<Long>> seen = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofDays(1))
            .maximumSize(10_000)
            .build();

    public InstrumentService(
            InstrumentRepository instruments,
            InstrumentPriceRepository prices,
            InvestmentOperationRepository operations,
            ListingSearch listings,
            Clock clock) {
        this.instruments = instruments;
        this.prices = prices;
        this.operations = operations;
        this.listings = listings;
        this.clock = clock;
    }

    /** The instruments the user has operations of or entered, with their latest prices. */
    @Transactional(readOnly = true)
    public List<InstrumentResponse> list(Long userId) {
        return instruments.findUsed(userId).stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public InstrumentResponse get(Long userId, Long id) {
        return response(find(userId, id));
    }

    /**
     * Own and known instruments matching the query first, then those of the provider (for at least
     * {@value #MIN_PROVIDER_QUERY} characters). The market instruments found may then be read by the user.
     */
    @Transactional(readOnly = true)
    public SearchResponse search(Long userId, String query) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<SearchResult> results = new ArrayList<>();
        for (Instrument instrument : instruments.findUsed(userId)) {
            if (matches(instrument, needle)) {
                results.add(result(instrument));
            }
        }
        if (listings.enabled() && needle.length() >= MIN_PROVIDER_QUERY) {
            for (Listing listing : listings.search(userId, query)) {
                boolean known = results.stream()
                        .anyMatch(result -> result.symbol().equals(listing.symbol())
                                && Objects.equals(result.micCode(), listing.micCode()));
                if (!known) {
                    Long id = instruments
                            .findMarket(listing.symbol(), listing.micCode())
                            .map(Instrument::getId)
                            .orElse(null);
                    if (id != null) {
                        seen(userId, id);
                    }
                    results.add(new SearchResult(
                            id,
                            listing.symbol(),
                            listing.name(),
                            type(listing.type()),
                            listing.currency(),
                            listing.exchange(),
                            listing.micCode(),
                            listing.country()));
                }
            }
        }
        return new SearchResponse(listings.enabled(), results);
    }

    /**
     * Adds a market instrument as the provider describes it, or returns the one already known. The data come from the
     * provider, not from the client, as the instrument is shared.
     */
    @Transactional
    public InstrumentResponse addMarket(Long userId, MarketInstrumentRequest request) {
        String micCode = request.micCode() == null || request.micCode().isBlank() ? null : request.micCode();
        Instrument instrument = findOrCreateMarket(
                userId,
                request.symbol(),
                () -> instruments.findMarket(request.symbol(), micCode),
                listing -> listing.symbol().equals(request.symbol()) && Objects.equals(listing.micCode(), micCode));
        if (instrument == null) {
            throw ApiException.invalid("symbol", "error.investment.unknownSymbol");
        }
        seen(userId, instrument.getId());
        return response(instrument);
    }

    /**
     * The fund of the benchmark (a US listing in dollars), added when first asked for and then priced like held
     * instruments; null when the provider does not know it.
     *
     * @throws ApiException when the provider is not configured or does not answer
     */
    @Transactional
    public Instrument benchmark(Benchmark benchmark) {
        Instrument fund = findOrCreateMarket(
                null,
                benchmark.symbol(),
                () -> instruments.findMarketBySymbol(benchmark.symbol()).stream()
                        .findFirst(),
                listing -> listing.symbol().equals(benchmark.symbol())
                        && listing.currency().equals("USD"));
        if (fund != null) {
            fund.setBenchmark(true);
        }
        return fund;
    }

    /** The known market instrument, else the first listing of the provider that matches, added; else null. */
    private Instrument findOrCreateMarket(
            Long userId, String symbol, Supplier<Optional<Instrument>> known, Predicate<Listing> match) {
        Optional<Instrument> existing = known.get();
        if (existing.isPresent()) {
            return existing.get();
        }
        if (!listings.enabled()) {
            throw new ApiException(ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE);
        }
        Listing listing = listings.search(userId, symbol).stream()
                .filter(match)
                .findFirst()
                .orElse(null);
        if (listing == null) {
            return null;
        }
        Instrument instrument =
                new Instrument(null, listing.symbol(), type(listing.type()), listing.currency(), Valuation.MARKET);
        instrument.setName(listing.name());
        instrument.setExchange(listing.exchange());
        instrument.setMicCode(listing.micCode());
        return instruments.save(instrument);
    }

    @Transactional
    public InstrumentResponse createOwn(Long userId, OwnInstrumentRequest request) {
        Instrument instrument = new Instrument(
                userId, request.symbol().trim(), request.type(), request.currency(), request.valuation());
        apply(instrument, request);
        return response(instruments.save(instrument));
    }

    @Transactional
    public InstrumentResponse updateOwn(Long userId, Long id, OwnInstrumentRequest request) {
        Instrument instrument = own(userId, id);
        instrument.setSymbol(request.symbol().trim());
        instrument.setType(request.type());
        instrument.setCurrency(request.currency());
        instrument.setValuation(request.valuation());
        apply(instrument, request);
        return response(instrument);
    }

    /** Only an own instrument without operations can be deleted. */
    @Transactional
    public void deleteOwn(Long userId, Long id) {
        Instrument instrument = own(userId, id);
        if (operations.existsByInstrumentId(id)) {
            throw new ApiException(ErrorCode.RESOURCE_IN_USE);
        }
        instruments.delete(instrument);
    }

    @Transactional(readOnly = true)
    public List<PricePoint> prices(Long userId, Long id, LocalDate from, LocalDate to) {
        Instrument instrument = find(userId, id);
        return prices.findByInstrumentIdAndDateBetweenOrderByDate(instrument.getId(), from, to).stream()
                .map(price -> new PricePoint(price.getDate(), price.getClose()))
                .toList();
    }

    /**
     * An instrument the user may use: one of their own, or a market instrument they hold, compare with, or found in
     * the last day. Other market instruments are not shown, as they tell what other users hold.
     */
    public Instrument find(Long userId, Long id) {
        return instruments
                .findById(id)
                .filter(instrument -> instrument.getUserId() != null
                        ? instrument.getUserId().equals(userId)
                        : instrument.isBenchmark()
                                || seenBy(userId, instrument.getId())
                                || operations.existsByInstrumentIdAndUserId(instrument.getId(), userId))
                .orElseThrow(ApiException::notFound);
    }

    private void seen(Long userId, Long instrumentId) {
        if (userId != null) {
            seen.asMap()
                    .computeIfAbsent(userId, user -> ConcurrentHashMap.newKeySet())
                    .add(instrumentId);
        }
    }

    private boolean seenBy(Long userId, Long instrumentId) {
        Set<Long> ids = seen.getIfPresent(userId);
        return ids != null && ids.contains(instrumentId);
    }

    private Instrument own(Long userId, Long id) {
        Instrument instrument = find(userId, id);
        if (instrument.getUserId() == null) {
            throw ApiException.invalid("id", "error.investment.marketInstrument");
        }
        return instrument;
    }

    private void apply(Instrument instrument, OwnInstrumentRequest request) {
        if (request.valuation() == Valuation.MARKET) {
            throw ApiException.invalid("valuation", "error.investment.ownValuation");
        }
        if (request.valuation() == Valuation.MANUAL && request.manualPrice() == null) {
            throw ApiException.invalid("manualPrice", "error.required");
        }
        if (request.valuation() == Valuation.NOMINAL && request.nominal() == null) {
            throw ApiException.invalid("nominal", "error.required");
        }
        if (request.couponFrequency() != null && !BondValuation.FREQUENCIES.contains(request.couponFrequency())) {
            throw ApiException.invalid("couponFrequency", "error.investment.couponFrequency");
        }
        if (request.maturityDate() != null) {
            Periods.checkDate(request.maturityDate(), "maturityDate");
        }
        if (request.manualPriceDate() != null) {
            Periods.checkDate(request.manualPriceDate(), "manualPriceDate");
        }
        instrument.setName(request.name().trim());
        instrument.setIsin(request.isin());
        instrument.setNominal(request.nominal());
        instrument.setCouponRate(request.couponRate());
        instrument.setCouponFrequency(request.couponFrequency());
        instrument.setMaturityDate(request.maturityDate());
        instrument.setManualPrice(request.manualPrice());
        instrument.setManualPriceDate(
                request.manualPrice() == null
                        ? null
                        : request.manualPriceDate() != null ? request.manualPriceDate() : LocalDate.now(clock));
    }

    private static boolean matches(Instrument instrument, String needle) {
        return needle.isEmpty()
                || instrument.getSymbol().toLowerCase(Locale.ROOT).contains(needle)
                || instrument.getName().toLowerCase(Locale.ROOT).contains(needle)
                || (instrument.getIsin() != null
                        && instrument.getIsin().toLowerCase(Locale.ROOT).equals(needle));
    }

    /** The kind of instrument from the name the provider gives it. */
    static InstrumentType type(String providerType) {
        if (providerType == null) {
            return InstrumentType.OTHER;
        }
        String type = providerType.toLowerCase(Locale.ROOT);
        if (type.contains("etf")) {
            return InstrumentType.ETF;
        }
        if (type.contains("stock") || type.contains("depositary") || type.equals("reit")) {
            return InstrumentType.STOCK;
        }
        if (type.contains("fund")) {
            return InstrumentType.FUND;
        }
        if (type.contains("bond")) {
            return InstrumentType.BOND;
        }
        if (type.contains("digital") || type.contains("crypto")) {
            return InstrumentType.CRYPTO;
        }
        return InstrumentType.OTHER;
    }

    private SearchResult result(Instrument instrument) {
        return new SearchResult(
                instrument.getId(),
                instrument.getSymbol(),
                instrument.getName(),
                instrument.getType(),
                instrument.getCurrency(),
                instrument.getExchange(),
                instrument.getMicCode(),
                null);
    }

    InstrumentResponse response(Instrument instrument) {
        LocalDate today = LocalDate.now(clock);
        BigDecimal price = null;
        LocalDate priceDate = null;
        switch (instrument.getValuation()) {
            case MARKET -> {
                List<InstrumentPrice> latest = prices.findLatest(List.of(instrument.getId()), today);
                if (!latest.isEmpty()) {
                    price = latest.get(0).getClose();
                    priceDate = latest.get(0).getDate();
                }
            }
            case NOMINAL -> {
                price = BondValuation.price(instrument, today);
                priceDate = price != null ? today : null;
            }
            case MANUAL -> {
                price = instrument.getManualPrice();
                priceDate = instrument.getManualPriceDate();
            }
        }
        return new InstrumentResponse(
                instrument.getId(),
                instrument.getSymbol(),
                instrument.getName(),
                instrument.getType(),
                instrument.getCurrency(),
                instrument.getExchange(),
                instrument.getMicCode(),
                instrument.getIsin(),
                instrument.getValuation(),
                instrument.getUserId() != null,
                instrument.getNominal(),
                instrument.getCouponRate(),
                instrument.getCouponFrequency(),
                instrument.getMaturityDate(),
                price,
                priceDate);
    }
}
