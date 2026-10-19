package com.andrewcorp.webwallet.imports.broker;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.util.Lists;
import com.andrewcorp.webwallet.imports.broker.BrokerImportDtos.BrokerConfirmRequest;
import com.andrewcorp.webwallet.imports.broker.BrokerImportDtos.BrokerImportResult;
import com.andrewcorp.webwallet.imports.broker.BrokerImportDtos.BrokerPreviewResponse;
import com.andrewcorp.webwallet.imports.broker.BrokerImportDtos.CashPreview;
import com.andrewcorp.webwallet.imports.broker.BrokerImportDtos.InstrumentChoice;
import com.andrewcorp.webwallet.imports.broker.BrokerImportDtos.OperationPreview;
import com.andrewcorp.webwallet.imports.broker.BrokerImportDtos.OperationStatus;
import com.andrewcorp.webwallet.imports.broker.BrokerImportDtos.PreviewAccount;
import com.andrewcorp.webwallet.imports.broker.BrokerImportDtos.TickerChoice;
import com.andrewcorp.webwallet.imports.broker.BrokerImportDtos.TickerPreview;
import com.andrewcorp.webwallet.imports.broker.BrokerStatement.BrokerOperation;
import com.andrewcorp.webwallet.imports.broker.BrokerStatement.BrokerTicker;
import com.andrewcorp.webwallet.imports.freedom.FreedomConverter;
import com.andrewcorp.webwallet.imports.freedom.FreedomReport;
import com.andrewcorp.webwallet.imports.preview.ImportPreview;
import com.andrewcorp.webwallet.imports.preview.ImportPreviewService;
import com.andrewcorp.webwallet.imports.preview.ImportSource;
import com.andrewcorp.webwallet.investment.Instrument;
import com.andrewcorp.webwallet.investment.InstrumentRepository;
import com.andrewcorp.webwallet.investment.InstrumentService;
import com.andrewcorp.webwallet.investment.InstrumentType;
import com.andrewcorp.webwallet.investment.InvestmentDtos.MarketInstrumentRequest;
import com.andrewcorp.webwallet.investment.InvestmentDtos.SearchResult;
import com.andrewcorp.webwallet.investment.InvestmentOperationRepository;
import com.andrewcorp.webwallet.investment.LotLedger;
import com.andrewcorp.webwallet.investment.Valuation;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.account.AccountService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Imports of broker reports with a preview. The preview finds an instrument for each ticker (one the user already
 * holds, else a market one of the provider, else one to create by hand) and runs the import in a transaction that is
 * rolled back, which shows the duplicates, the money and the positions after it. The reports are kept for an hour;
 * the confirmation imports them with the instruments and the operations the user chose.
 */
@Service
public class BrokerImportService {

    /** Exchanges of the United States, for tickers such as {@code SCHD.US}. */
    private static final Set<String> US_EXCHANGES =
            Set.of("XNYS", "XNAS", "XNGS", "XNMS", "XNCM", "ARCX", "BATS", "XASE");

    private static final Map<String, String> MARKET_COUNTRIES =
            Map.of("US", "United States", "EU", "Germany", "KZ", "Kazakhstan");

    private final BrokerImporter importer;
    private final ImportPreviewService previews;
    private final InstrumentService instrumentService;
    private final InstrumentRepository instruments;
    private final InvestmentOperationRepository operations;
    private final AccountService accountService;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate tx;

    public BrokerImportService(
            BrokerImporter importer,
            ImportPreviewService previews,
            InstrumentService instrumentService,
            InstrumentRepository instruments,
            InvestmentOperationRepository operations,
            AccountService accountService,
            JsonMapper jsonMapper,
            PlatformTransactionManager transactionManager) {
        this.importer = importer;
        this.previews = previews;
        this.instrumentService = instrumentService;
        this.instruments = instruments;
        this.operations = operations;
        this.accountService = accountService;
        this.jsonMapper = jsonMapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** The reports of a Freedom Finance preview, imported on confirmation. */
    record FreedomInput(Long accountId, FreedomReport report) {}

    /** What the preview chose for each ticker. */
    record StoredChoices(Map<String, InstrumentChoice> suggestions) {}

    public BrokerPreviewResponse previewFreedom(Long userId, Long accountId, FreedomReport report) {
        BrokerStatement statement = FreedomConverter.convert(report);
        boolean[] providerUnavailable = {false};
        // The provider is asked before the transaction, which it must not hold open
        Map<String, InstrumentChoice> suggestions = suggest(userId, statement.tickers(), providerUnavailable);
        Shown shown = tx.execute(status -> {
            status.setRollbackOnly();
            BrokerImporter.Result result = importer.run(
                    userId,
                    accountId,
                    statement,
                    Set.of(),
                    ticker -> instrument(userId, ticker, suggestions.get(ticker.ticker())));
            return shown(userId, statement, result, suggestions);
        });
        ImportPreview preview = previews.store(
                userId, ImportSource.FREEDOM, new FreedomInput(accountId, report), new StoredChoices(suggestions));
        List<BrokerOperation> rows = statement.operations();
        return new BrokerPreviewResponse(
                preview.getId(),
                ImportSource.FREEDOM,
                preview.getExpiresAt(),
                shown.account(),
                rows.isEmpty() ? null : rows.get(0).time().toLocalDate(),
                rows.isEmpty() ? null : rows.get(rows.size() - 1).time().toLocalDate(),
                shown.tickers(),
                shown.operations(),
                shown.cash(),
                statement.merged(),
                providerUnavailable[0]);
    }

    /**
     * Imports the reports of the preview with the instruments and operations the user chose, and removes the preview.
     * Rejected when the reports sell more than they buy before.
     */
    @Transactional
    public BrokerImportResult confirmFreedom(Long userId, UUID id, BrokerConfirmRequest request) {
        ImportPreview preview = previews.find(userId, id, ImportSource.FREEDOM);
        FreedomInput input = jsonMapper.readValue(preview.getInput(), FreedomInput.class);
        Map<String, InstrumentChoice> suggestions =
                jsonMapper.readValue(preview.getPreview(), StoredChoices.class).suggestions();
        BrokerStatement statement = FreedomConverter.convert(input.report());

        Map<String, TickerChoice> chosen = new HashMap<>();
        Lists.orEmpty(request.instruments()).forEach(choice -> chosen.put(choice.ticker(), choice));
        int[] created = {0};
        BrokerImporter.Result result = importer.run(
                userId, input.accountId(), statement, new HashSet<>(Lists.orEmpty(request.excluded())), ticker -> {
                    Instrument instrument = chosen(userId, ticker, chosen.get(ticker.ticker()), suggestions);
                    if (instrument.getUserId() != null && instrument.getId() == null) {
                        created[0]++;
                    }
                    return instrument.getId() != null ? instrument : instruments.save(instrument);
                });
        if (!result.shortfalls().isEmpty()) {
            BrokerImporter.Shortfall first = result.shortfalls().get(0);
            throw ApiException.invalid("operations", "error.import.shortfall", first.ticker(), first.date());
        }
        previews.delete(preview);
        return new BrokerImportResult(
                result.account().getId(),
                result.accountCreated(),
                created[0],
                result.imported().size(),
                result.duplicate().size());
    }

    /** A known instrument of the same symbol and currency, else a listing of the provider, else one by hand. */
    private Map<String, InstrumentChoice> suggest(
            Long userId, List<BrokerTicker> tickers, boolean[] providerUnavailable) {
        List<Instrument> used = instruments.findUsed(userId);
        Map<String, InstrumentChoice> suggestions = new LinkedHashMap<>();
        for (BrokerTicker ticker : tickers) {
            Optional<Instrument> known = used.stream()
                    .filter(instrument -> instrument.getSymbol().equalsIgnoreCase(ticker.symbol())
                            && instrument.getCurrency().equals(ticker.currency()))
                    .findFirst();
            InstrumentChoice choice = known.map(BrokerImportService::choice).orElse(null);
            if (choice == null && !providerUnavailable[0] && MARKET_COUNTRIES.containsKey(ticker.market())) {
                try {
                    choice = listing(userId, ticker);
                } catch (ApiException e) {
                    providerUnavailable[0] = true;
                }
            }
            suggestions.put(ticker.ticker(), choice != null ? choice : manual(ticker));
        }
        return suggestions;
    }

    private InstrumentChoice listing(Long userId, BrokerTicker ticker) {
        for (SearchResult result :
                instrumentService.search(userId, ticker.symbol()).results()) {
            boolean market = ticker.market().equals("US")
                    ? US_EXCHANGES.contains(result.micCode())
                            || MARKET_COUNTRIES.get("US").equals(result.country())
                    : MARKET_COUNTRIES.get(ticker.market()).equals(result.country());
            if (result.symbol().equalsIgnoreCase(ticker.symbol())
                    && result.currency().equals(ticker.currency())
                    && market) {
                return new InstrumentChoice(
                        result.id(),
                        result.symbol(),
                        result.micCode(),
                        result.name(),
                        result.type(),
                        result.currency(),
                        result.exchange(),
                        false);
            }
        }
        return null;
    }

    private static InstrumentChoice choice(Instrument instrument) {
        return new InstrumentChoice(
                instrument.getId(),
                instrument.getSymbol(),
                instrument.getMicCode(),
                instrument.getName(),
                instrument.getType(),
                instrument.getCurrency(),
                instrument.getExchange(),
                false);
    }

    private static InstrumentChoice manual(BrokerTicker ticker) {
        return new InstrumentChoice(
                null, ticker.symbol(), null, ticker.symbol(), InstrumentType.STOCK, ticker.currency(), null, true);
    }

    /** The instrument of a suggestion inside the preview: what the confirmation would create is created and undone. */
    private Instrument instrument(Long userId, BrokerTicker ticker, InstrumentChoice choice) {
        if (choice.instrumentId() != null) {
            return instrumentService.find(userId, choice.instrumentId());
        }
        Instrument instrument = new Instrument(
                choice.manual() ? userId : null,
                choice.symbol(),
                choice.type(),
                choice.currency(),
                choice.manual() ? Valuation.MANUAL : Valuation.MARKET);
        instrument.setName(choice.name());
        instrument.setExchange(choice.exchange());
        instrument.setMicCode(choice.micCode());
        return instruments.save(instrument);
    }

    /**
     * The instrument the user chose for a ticker, else the suggestion: a known one, a market one added from the
     * provider, or a new one by hand (not saved yet). It must be in the currency of the ticker.
     */
    private Instrument chosen(
            Long userId, BrokerTicker ticker, TickerChoice choice, Map<String, InstrumentChoice> suggestions) {
        InstrumentChoice suggestion = suggestions.getOrDefault(ticker.ticker(), manual(ticker));
        Long instrumentId = choice != null ? choice.instrumentId() : suggestion.instrumentId();
        String symbol = choice != null ? choice.symbol() : suggestion.manual() ? null : suggestion.symbol();
        String micCode = choice != null ? choice.micCode() : suggestion.micCode();
        boolean manual = choice != null ? Boolean.TRUE.equals(choice.manual()) : suggestion.manual();

        Instrument instrument;
        if (instrumentId != null) {
            try {
                instrument = instrumentService.find(userId, instrumentId);
            } catch (ApiException e) {
                throw ApiException.invalid("instruments", "error.notFound");
            }
        } else if (symbol != null && !manual) {
            Long id = instrumentService
                    .addMarket(userId, new MarketInstrumentRequest(symbol, micCode))
                    .id();
            instrument = instrumentService.find(userId, id);
        } else {
            instrument =
                    new Instrument(userId, ticker.symbol(), InstrumentType.STOCK, ticker.currency(), Valuation.MANUAL);
            instrument.setName(ticker.symbol());
        }
        if (!instrument.getCurrency().equals(ticker.currency())) {
            throw ApiException.invalid(
                    "instruments", "error.import.instrumentCurrency", ticker.ticker(), ticker.currency());
        }
        return instrument;
    }

    /** What the dry run shows. */
    private record Shown(
            PreviewAccount account,
            List<TickerPreview> tickers,
            List<OperationPreview> operations,
            List<CashPreview> cash) {}

    private Shown shown(
            Long userId,
            BrokerStatement statement,
            BrokerImporter.Result result,
            Map<String, InstrumentChoice> suggestions) {
        Account account = result.account();
        Set<String> shortTickers = new HashSet<>();
        result.shortfalls().forEach(shortfall -> shortTickers.add(shortfall.ticker()));

        List<TickerPreview> tickers = new ArrayList<>();
        for (BrokerTicker ticker : statement.tickers()) {
            Instrument held = result.holdings().get(ticker.ticker());
            BigDecimal quantity = held != null
                    ? LotLedger.lenient(operations.findByAccountAndInstrument(account, held))
                            .quantity()
                    : BigDecimal.ZERO;
            tickers.add(new TickerPreview(
                    ticker.ticker(),
                    ticker.currency(),
                    suggestions.get(ticker.ticker()),
                    quantity,
                    shortTickers.contains(ticker.ticker())));
        }

        // The ledger part (transfers, opening balance) and every operation of the account, the new ones included
        Map<String, BigDecimal> cash = new TreeMap<>();
        cash.put(
                account.getCurrency(),
                accountService.ledgerBalances(userId).getOrDefault(account.getId(), account.getOpeningBalance()));
        operations.findAllOfUser(userId).stream()
                .filter(operation -> operation.getAccount().getId().equals(account.getId()))
                .forEach(operation -> operation
                        .cashMoves()
                        .forEach((currency, amount) -> cash.merge(currency, amount, BigDecimal::add)));

        List<OperationPreview> rows = statement.operations().stream()
                .sorted(Comparator.comparing(BrokerOperation::time).reversed())
                .map(row -> row(row, result))
                .toList();
        return new Shown(
                new PreviewAccount(
                        result.accountCreated() ? null : account.getId(), account.getName(), account.getCurrency()),
                tickers,
                rows,
                cash.entrySet().stream()
                        .map(entry -> new CashPreview(entry.getKey(), entry.getValue()))
                        .toList());
    }

    private static OperationPreview row(BrokerOperation row, BrokerImporter.Result result) {
        OperationStatus status = row.skip() != null
                ? OperationStatus.SKIPPED
                : result.duplicate().contains(row.key()) ? OperationStatus.DUPLICATE : OperationStatus.NEW;
        BigDecimal cash = row.type() != null ? row.type().cash(row.amount(), row.fee(), row.tax()) : row.amount();
        return new OperationPreview(
                row.key(),
                row.time().toLocalDate(),
                row.type(),
                row.ticker(),
                row.quantity(),
                row.price(),
                row.amount(),
                row.fee(),
                row.tax(),
                row.currency(),
                cash,
                row.toAmount(),
                row.toCurrency(),
                row.description(),
                status,
                row.skip());
    }
}
