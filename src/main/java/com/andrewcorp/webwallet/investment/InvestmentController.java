package com.andrewcorp.webwallet.investment;

import com.andrewcorp.webwallet.common.validation.CurrencyCode;
import com.andrewcorp.webwallet.common.web.CurrentUserId;
import com.andrewcorp.webwallet.common.web.PageResponse;
import com.andrewcorp.webwallet.common.web.Periods;
import com.andrewcorp.webwallet.investment.InvestmentDtos.InstrumentResponse;
import com.andrewcorp.webwallet.investment.InvestmentDtos.MarketInstrumentRequest;
import com.andrewcorp.webwallet.investment.InvestmentDtos.OperationRequest;
import com.andrewcorp.webwallet.investment.InvestmentDtos.OperationResponse;
import com.andrewcorp.webwallet.investment.InvestmentDtos.OwnInstrumentRequest;
import com.andrewcorp.webwallet.investment.InvestmentDtos.PortfolioResponse;
import com.andrewcorp.webwallet.investment.InvestmentDtos.PositionDetailResponse;
import com.andrewcorp.webwallet.investment.InvestmentDtos.PricePoint;
import com.andrewcorp.webwallet.investment.InvestmentDtos.SearchResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Investments")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/v1/investments")
public class InvestmentController {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("date"), Sort.Order.desc("id"));
    private static final int MAX_PRICE_YEARS = 20;

    private final PortfolioService portfolioService;
    private final InstrumentService instrumentService;
    private final OperationService operationService;
    private final HistoryService historyService;
    private final DividendCalendarService dividendCalendarService;
    private final Clock clock;

    public InvestmentController(
            PortfolioService portfolioService,
            InstrumentService instrumentService,
            OperationService operationService,
            HistoryService historyService,
            DividendCalendarService dividendCalendarService,
            Clock clock) {
        this.portfolioService = portfolioService;
        this.instrumentService = instrumentService;
        this.operationService = operationService;
        this.historyService = historyService;
        this.dividendCalendarService = dividendCalendarService;
        this.clock = clock;
    }

    @Operation(
            summary = "The portfolio of the brokerage accounts in the base currency",
            description = "All brokerage accounts that are not archived, or the given one.")
    @GetMapping("/portfolio")
    public PortfolioResponse portfolio(@CurrentUserId Long userId, @RequestParam(required = false) Long accountId) {
        return portfolioService.portfolio(userId, accountId);
    }

    @Operation(
            summary = "The value of the brokerage accounts day by day, the net deposits and a benchmark",
            description = "Cash and positions at the closes of each day in the currency (by default the base one) at"
                    + " the NBU rates of the day. The benchmark is what the same deposits would be worth in the index"
                    + " fund, bought and sold at the closes of their days; its prices load within minutes after it is"
                    + " first asked for. Periods over " + HistoryService.MAX_DAILY_POINTS + " days are sampled weekly.")
    @GetMapping("/history")
    public HistoryService.HistoryResponse history(
            @CurrentUserId Long userId,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @CurrencyCode String currency,
            @RequestParam(required = false) Benchmark benchmark) {
        return historyService.history(userId, accountId, from, to, currency, benchmark);
    }

    @Operation(
            summary = "Dividends and coupons: received, expected, declared and forecast, by day and by month",
            description = "By default from a year ago to a year ahead, at most 5 years. Received ones are the"
                    + " operations; expected ones were due to a holder on the ex-dividend date but are not recorded;"
                    + " declared ones come from the dividend provider or the coupon schedule of a bond; the forecast"
                    + " repeats the payments of the last 12 months for what is held now.")
    @GetMapping("/dividends")
    public DividendCalendarService.CalendarResponse dividends(
            @CurrentUserId Long userId,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @CurrencyCode String currency) {
        return dividendCalendarService.calendar(userId, accountId, from, to, currency);
    }

    @Operation(summary = "One instrument on one account: the position, its lots (FIFO), sales and operations")
    @GetMapping("/positions")
    public PositionDetailResponse position(
            @CurrentUserId Long userId, @RequestParam Long accountId, @RequestParam Long instrumentId) {
        return portfolioService.position(userId, accountId, instrumentId);
    }

    @Operation(summary = "Instruments of the user's operations and those entered by hand, with the latest prices")
    @GetMapping("/instruments")
    public List<InstrumentResponse> instruments(@CurrentUserId Long userId) {
        return instrumentService.list(userId);
    }

    @Operation(
            summary = "Find instruments by ticker, name or ISIN",
            description = "Own and known instruments first, then those of the market data provider; a result without"
                    + " an id is added with POST /instruments/market.")
    @GetMapping("/instruments/search")
    public SearchResponse search(@CurrentUserId Long userId, @RequestParam @Size(min = 1, max = 50) String q) {
        return instrumentService.search(userId, q);
    }

    @GetMapping("/instruments/{id}")
    public InstrumentResponse instrument(@CurrentUserId Long userId, @PathVariable Long id) {
        return instrumentService.get(userId, id);
    }

    @Operation(
            summary = "Add a market instrument found by a search, or get the one already known",
            description = "Its data come from the market data provider; prices load within minutes.")
    @PostMapping("/instruments/market")
    public InstrumentResponse addMarket(
            @CurrentUserId Long userId, @Valid @RequestBody MarketInstrumentRequest request) {
        return instrumentService.addMarket(userId, request);
    }

    @Operation(summary = "Enter an instrument by hand, e.g. a Ukrainian government bond")
    @PostMapping("/instruments")
    @ResponseStatus(HttpStatus.CREATED)
    public InstrumentResponse createOwn(@CurrentUserId Long userId, @Valid @RequestBody OwnInstrumentRequest request) {
        return instrumentService.createOwn(userId, request);
    }

    @Operation(summary = "Replace an instrument entered by hand, e.g. with a new manual price")
    @PutMapping("/instruments/{id}")
    public InstrumentResponse updateOwn(
            @CurrentUserId Long userId, @PathVariable Long id, @Valid @RequestBody OwnInstrumentRequest request) {
        return instrumentService.updateOwn(userId, id, request);
    }

    @Operation(summary = "Delete an instrument entered by hand that has no operations")
    @DeleteMapping("/instruments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteOwn(@CurrentUserId Long userId, @PathVariable Long id) {
        instrumentService.deleteOwn(userId, id);
    }

    @Operation(summary = "Daily closes of a market instrument, by default of the last year")
    @GetMapping("/instruments/{id}/prices")
    public List<PricePoint> prices(
            @CurrentUserId Long userId,
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now(clock);
        LocalDate start = from != null ? from : end.minusYears(1);
        Periods.checkYears(start, end, MAX_PRICE_YEARS, "from");
        return instrumentService.prices(userId, id, start, end);
    }

    @Operation(summary = "Operations of the brokerage accounts, newest first")
    @GetMapping("/operations")
    public PageResponse<OperationResponse> operations(
            @CurrentUserId Long userId,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) Long instrumentId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return PageResponse.of(
                operationService.list(userId, accountId, instrumentId, PageRequest.of(page, size, NEWEST_FIRST)),
                operation -> operation);
    }

    @GetMapping("/operations/{id}")
    public OperationResponse operation(@CurrentUserId Long userId, @PathVariable Long id) {
        return operationService.get(userId, id);
    }

    @Operation(
            summary = "Record a trade, a dividend or coupon, a fee or a split",
            description = "A sale of more than is held on its date is rejected.")
    @PostMapping("/operations")
    @ResponseStatus(HttpStatus.CREATED)
    public OperationResponse create(@CurrentUserId Long userId, @Valid @RequestBody OperationRequest request) {
        return operationService.create(userId, request);
    }

    @PutMapping("/operations/{id}")
    public OperationResponse update(
            @CurrentUserId Long userId, @PathVariable Long id, @Valid @RequestBody OperationRequest request) {
        return operationService.update(userId, id, request);
    }

    @Operation(summary = "Delete an operation; deleting a purchase whose units were sold later is rejected")
    @DeleteMapping("/operations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUserId Long userId, @PathVariable Long id) {
        operationService.delete(userId, id);
    }
}
