package com.andrewcorp.webwallet.investment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.TestClock;
import com.andrewcorp.webwallet.common.util.Zones;
import com.andrewcorp.webwallet.currency.ExchangeRate;
import com.andrewcorp.webwallet.currency.ExchangeRateRepository;
import com.andrewcorp.webwallet.investment.market.DividendProvider;
import com.andrewcorp.webwallet.investment.market.DividendProvider.Dividend;
import com.andrewcorp.webwallet.investment.market.MarketDataProvider;
import com.andrewcorp.webwallet.investment.market.MarketDataProvider.DailyClose;
import com.andrewcorp.webwallet.investment.market.MarketDataProvider.Listing;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SuppressWarnings("unchecked")
class InvestmentControllerTest extends AbstractIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2030, 3, 15);
    private static final Listing AAPL =
            new Listing("AAPL", "Apple Inc", "NASDAQ", "XNGS", "United States", "USD", "Common Stock");

    @MockitoBean
    private MarketDataProvider provider;

    @MockitoBean
    private DividendProvider dividendProvider;

    @Autowired
    private DividendService dividendService;

    @Autowired
    private ExchangeRateRepository rates;

    @Autowired
    private PriceService priceService;

    @Autowired
    private InstrumentPriceRepository prices;

    @Autowired
    private InstrumentRepository instruments;

    private String auth;
    private String other;
    private Long broker;
    private Long card;

    @BeforeEach
    void setUp() throws Exception {
        // The access token is checked against the real time, so sign in before moving the clock
        auth = loginNewUser();
        other = loginNewUser();
        TestClock.INSTANCE.set(TODAY.atTime(LocalTime.NOON).atZone(Zones.KYIV).toInstant());
        rates.save(new ExchangeRate("USD", LocalDate.of(2030, 1, 1), new BigDecimal("40")));
        when(provider.enabled()).thenReturn(true);
        when(provider.search(any())).thenReturn(List.of(AAPL));
        broker = account("Interactive Brokers", "INVESTMENT", "USD", 0);
        card = account("Card", "CARD", "UAH", 100000);
    }

    private Long account(String name, String type, String currency, double openingBalance) throws Exception {
        return id(json(
                postJson(
                        "/api/v1/accounts",
                        Map.of("name", name, "type", type, "currency", currency, "openingBalance", openingBalance),
                        auth),
                201));
    }

    private Long apple() throws Exception {
        return id(json(
                postJson("/api/v1/investments/instruments/market", Map.of("symbol", "AAPL", "micCode", "XNGS"), auth),
                200));
    }

    private Long operation(Map<String, Object> body) throws Exception {
        return id(json(postJson("/api/v1/investments/operations", body, auth), 201));
    }

    private Map<String, Object> trade(
            String type, String date, Long instrument, double quantity, double price, double fee) {
        Map<String, Object> body = new HashMap<>(Map.of(
                "type", type,
                "accountId", broker,
                "instrumentId", instrument,
                "date", date,
                "quantity", quantity,
                "price", price,
                "fee", fee));
        return body;
    }

    private static double number(Object value) {
        return ((Number) value).doubleValue();
    }

    @Test
    void buildsThePortfolioFromTradesAndPrices() throws Exception {
        // 5 000 USD brought from the card
        json(
                postJson(
                        "/api/v1/transactions",
                        Map.of(
                                "type",
                                "TRANSFER",
                                "date",
                                "2030-01-02",
                                "accountId",
                                card,
                                "toAccountId",
                                broker,
                                "amount",
                                200000,
                                "toAmount",
                                5000),
                        auth),
                201);
        Long aapl = apple();
        // The same instrument again is the one already known
        assertThat(apple()).isEqualTo(aapl);
        operation(trade("BUY", "2030-01-10", aapl, 10, 100, 1));
        operation(trade("BUY", "2030-02-10", aapl, 10, 200, 0));
        operation(trade("SELL", "2030-03-01", aapl, 5, 250, 0));
        operation(Map.of(
                "type",
                "DIVIDEND",
                "accountId",
                broker,
                "instrumentId",
                aapl,
                "date",
                "2030-03-05",
                "amount",
                10,
                "tax",
                1.5));
        operation(Map.of("type", "FEE", "accountId", broker, "date", "2030-03-06", "amount", 2.5));

        when(provider.closes(eq("AAPL"), eq("XNGS"), any(), eq(TODAY)))
                .thenReturn(List.of(
                        new DailyClose(LocalDate.of(2030, 3, 13), new BigDecimal("210")),
                        new DailyClose(LocalDate.of(2030, 3, 14), new BigDecimal("220"))));
        assertThat(priceService.due(10)).containsExactly(aapl);
        priceService.update(aapl);
        assertThat(priceService.due(10)).isEmpty();

        Map<String, Object> portfolio =
                json(get("/api/v1/investments/portfolio").header("Authorization", auth), 200);

        // Cash: 5 000 - 1 001 - 2 000 + 1 250 + 8.50 - 2.50 = 3 255 USD; 15 shares at 220 = 3 300 USD
        assertThat(portfolio).containsEntry("currency", "UAH").containsEntry("pricesAsOf", "2030-03-14");
        // The 5 000 USD brought from the card, at the rate of their day
        assertThat(number(portfolio.get("deposited"))).isEqualTo(5000 * 40.0);
        assertThat(number(portfolio.get("value"))).isEqualTo((3255 + 3300) * 40.0);
        // 5 of the first lot at 100.10 and 10 at 200
        assertThat(number(portfolio.get("invested"))).isEqualTo(2500.5 * 40);
        assertThat(number(portfolio.get("unrealized"))).isEqualTo((3300 - 2500.5) * 40);
        // FIFO: 5 * 250 - 5 * 100.10
        assertThat(number(portfolio.get("realizedThisYear"))).isEqualTo(749.5 * 40);
        assertThat(number(portfolio.get("dividendsLast12Months"))).isEqualTo(8.5 * 40);
        assertThat(number(portfolio.get("dayChange"))).isEqualTo(15 * 10 * 40.0);
        List<Map<String, Object>> positions = (List<Map<String, Object>>) portfolio.get("positions");
        assertThat(positions).hasSize(1);
        assertThat(positions.get(0))
                .containsEntry("quantity", 15.0)
                .containsEntry("price", 220.0)
                .containsEntry("dayChange", 10.0);
        assertThat(number(positions.get(0).get("averagePrice"))).isEqualTo(166.7);
        List<Map<String, Object>> cash = (List<Map<String, Object>>) portfolio.get("cash");
        assertThat(cash).hasSize(1);
        assertThat(number(cash.get(0).get("amount"))).isEqualTo(3255);
        assertThat(portfolio.get("byType").toString()).contains("key=STOCK").contains("key=CASH");

        // The same portfolio in dollars
        Map<String, Object> inUsd = json(
                get("/api/v1/investments/portfolio").param("currency", "USD").header("Authorization", auth), 200);
        assertThat(inUsd).containsEntry("currency", "USD");
        assertThat(number(inUsd.get("value"))).isEqualTo(3255 + 3300);
        assertThat(number(inUsd.get("deposited"))).isEqualTo(5000);
        assertThat(number(((List<Map<String, Object>>) inUsd.get("positions"))
                        .get(0)
                        .get("valueInBase")))
                .isEqualTo(3300);

        // The brokerage account is worth its cash and its positions
        Map<String, Object> account = json(get("/api/v1/accounts/" + broker).header("Authorization", auth), 200);
        assertThat(number(account.get("balance"))).isEqualTo(6555);

        Map<String, Object> position = json(
                get("/api/v1/investments/positions")
                        .param("accountId", broker.toString())
                        .param("instrumentId", aapl.toString())
                        .header("Authorization", auth),
                200);
        assertThat(number(position.get("realized"))).isEqualTo(749.5);
        assertThat(number(position.get("dividends"))).isEqualTo(8.5);
        List<Map<String, Object>> lots = (List<Map<String, Object>>) position.get("lots");
        assertThat(lots).extracting(lot -> number(lot.get("remaining"))).containsExactly(5.0, 10.0);
        assertThat(number(lots.get(1).get("unrealized"))).isEqualTo(200);
        assertThat((List<?>) position.get("sales")).hasSize(1);
        assertThat((List<?>) position.get("operations")).hasSize(4);

        List<?> prices = jsonMapper.readValue(
                mockMvc.perform(get("/api/v1/investments/instruments/" + aapl + "/prices")
                                .header("Authorization", auth))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                List.class);
        assertThat(prices).hasSize(2);
    }

    @Test
    void rejectsSellingMoreThanHeld() throws Exception {
        Long aapl = apple();
        Long buy = operation(trade("BUY", "2030-01-10", aapl, 10, 100, 0));
        json(postJson("/api/v1/investments/operations", trade("SELL", "2030-01-09", aapl, 5, 100, 0), auth), 400);
        operation(trade("SELL", "2030-02-01", aapl, 10, 120, 0));
        // The sold purchase cannot go or shrink
        json(delete("/api/v1/investments/operations/" + buy).header("Authorization", auth), 400);
        json(putJson("/api/v1/investments/operations/" + buy, trade("BUY", "2030-01-10", aapl, 5, 100, 0), auth), 400);

        // Operations belong to brokerage accounts
        Map<String, Object> onCard = trade("BUY", "2030-01-10", aapl, 1, 100, 0);
        onCard.put("accountId", card);
        json(postJson("/api/v1/investments/operations", onCard, auth), 400);
        Map<String, Object> noPrice = trade("BUY", "2030-01-10", aapl, 1, 100, 0);
        noPrice.remove("price");
        json(postJson("/api/v1/investments/operations", noPrice, auth), 400);
        json(
                postJson(
                        "/api/v1/investments/operations",
                        Map.of(
                                "type",
                                "DIVIDEND",
                                "accountId",
                                broker,
                                "instrumentId",
                                aapl,
                                "date",
                                "2030-03-05",
                                "amount",
                                10,
                                "tax",
                                11),
                        auth),
                400);
        // An account with operations cannot be deleted
        json(delete("/api/v1/accounts/" + broker).header("Authorization", auth), 409);
    }

    @Test
    void recordsDepositsWithdrawalsAndExchanges() throws Exception {
        rates.save(new ExchangeRate("EUR", LocalDate.of(2030, 1, 1), new BigDecimal("44")));
        operation(
                Map.of("type", "DEPOSIT", "accountId", broker, "date", "2030-01-10", "amount", 100, "currency", "EUR"));
        Long exchange = operation(Map.of(
                "type",
                "EXCHANGE",
                "accountId",
                broker,
                "date",
                "2030-01-11",
                "amount",
                100,
                "fee",
                1,
                "currency",
                "EUR",
                "toAmount",
                108,
                "toCurrency",
                "USD"));
        operation(Map.of("type", "WITHDRAWAL", "accountId", broker, "date", "2030-02-01", "amount", 8));

        Map<String, Object> saved =
                json(get("/api/v1/investments/operations/" + exchange).header("Authorization", auth), 200);
        assertThat(saved).containsEntry("toCurrency", "USD").containsEntry("instrumentId", null);
        assertThat(number(saved.get("cash"))).isEqualTo(-101);
        assertThat(number(saved.get("toAmount"))).isEqualTo(108);

        Map<String, Object> portfolio = json(
                get("/api/v1/investments/portfolio").param("currency", "USD").header("Authorization", auth), 200);
        // 100 EUR at 44 / 40 brought, 8 USD taken
        assertThat(number(portfolio.get("deposited"))).isEqualTo(102);
        Map<String, Double> cash = new HashMap<>();
        ((List<Map<String, Object>>) portfolio.get("cash"))
                .forEach(item -> cash.put((String) item.get("currency"), number(item.get("amount"))));
        assertThat(cash).containsEntry("USD", 100.0).containsEntry("EUR", -1.0);
        Map<String, Object> account = json(get("/api/v1/accounts/" + broker).header("Authorization", auth), 200);
        // 100 USD and −1 EUR (−1.1 USD)
        assertThat(number(account.get("balance"))).isEqualTo(98.9);

        Map<String, Object> history =
                json(get("/api/v1/investments/history").param("currency", "USD").header("Authorization", auth), 200);
        List<Map<String, Object>> points = (List<Map<String, Object>>) history.get("points");
        assertThat(number(points.get(points.size() - 1).get("deposited"))).isEqualTo(102);

        Map<String, Object> movements = json(
                get("/api/v1/investments/operations")
                        .param("accountId", broker.toString())
                        .param("types", "DEPOSIT", "WITHDRAWAL")
                        .header("Authorization", auth),
                200);
        assertThat((List<Map<String, Object>>) movements.get("items"))
                .extracting(item -> item.get("type"))
                .containsExactly("WITHDRAWAL", "DEPOSIT");
        Map<String, Object> everything = json(
                get("/api/v1/investments/operations")
                        .param("accountId", broker.toString())
                        .header("Authorization", auth),
                200);
        assertThat(everything).containsEntry("totalItems", 3);

        // Money movements have no instrument; an exchange is between two currencies
        Long aapl = apple();
        json(
                postJson(
                        "/api/v1/investments/operations",
                        Map.of(
                                "type",
                                "DEPOSIT",
                                "accountId",
                                broker,
                                "instrumentId",
                                aapl,
                                "date",
                                "2030-01-10",
                                "amount",
                                1),
                        auth),
                400);
        json(
                postJson(
                        "/api/v1/investments/operations",
                        Map.of(
                                "type",
                                "EXCHANGE",
                                "accountId",
                                broker,
                                "date",
                                "2030-01-10",
                                "amount",
                                1,
                                "currency",
                                "USD",
                                "toAmount",
                                1,
                                "toCurrency",
                                "USD"),
                        auth),
                400);
        json(
                postJson(
                        "/api/v1/investments/operations",
                        Map.of("type", "EXCHANGE", "accountId", broker, "date", "2030-01-10", "amount", 1),
                        auth),
                400);
    }

    @Test
    void valuesBondsEnteredByHand() throws Exception {
        Long uah = account("ICU", "INVESTMENT", "UAH", 0);
        Map<String, Object> bond = new HashMap<>(Map.of(
                "symbol", "UA4000227045",
                "name", "ОВДП 22.07.2027",
                "type", "GOVERNMENT_BOND",
                "currency", "UAH",
                "isin", "UA4000227045",
                "valuation", "NOMINAL",
                "nominal", 1000,
                "couponRate", 15.2,
                "couponFrequency", 2,
                "maturityDate", "2027-07-22"));
        // Matured already on the date of the test: worth the nominal
        Long id = id(json(postJson("/api/v1/investments/instruments", bond, auth), 201));
        Map<String, Object> buy = new HashMap<>(Map.of(
                "type",
                "BUY",
                "accountId",
                uah,
                "instrumentId",
                id,
                "date",
                "2027-01-10",
                "quantity",
                100,
                "price",
                1010));
        operation(buy);

        Map<String, Object> portfolio = json(
                get("/api/v1/investments/portfolio")
                        .param("accountId", uah.toString())
                        .header("Authorization", auth),
                200);
        List<Map<String, Object>> positions = (List<Map<String, Object>>) portfolio.get("positions");
        assertThat(number(positions.get(0).get("value"))).isEqualTo(100000);
        assertThat(number(positions.get(0).get("unrealized"))).isEqualTo(-1000);

        bond.put("valuation", "MANUAL");
        bond.put("manualPrice", 990);
        json(putJson("/api/v1/investments/instruments/" + id, bond, auth), 200);
        assertThat(json(get("/api/v1/investments/instruments/" + id).header("Authorization", auth), 200))
                .containsEntry("price", 990.0)
                .containsEntry("priceDate", "2030-03-15")
                .containsEntry("own", true);
        json(delete("/api/v1/investments/instruments/" + id).header("Authorization", auth), 409);

        bond.put("valuation", "MARKET");
        json(putJson("/api/v1/investments/instruments/" + id, bond, auth), 400);
        bond.put("valuation", "MANUAL");
        bond.remove("manualPrice");
        json(putJson("/api/v1/investments/instruments/" + id, bond, auth), 400);
    }

    @Test
    void searchesOwnAndMarketInstruments() throws Exception {
        Long aapl = apple();
        when(provider.search("app"))
                .thenReturn(List.of(
                        AAPL,
                        new Listing("APP", "AppLovin", "NASDAQ", "XNGS", "United States", "USD", "Common Stock")));

        Map<String, Object> found = json(
                get("/api/v1/investments/instruments/search").param("q", "app").header("Authorization", auth), 200);

        assertThat(found).containsEntry("providerEnabled", true);
        List<Map<String, Object>> results = (List<Map<String, Object>>) found.get("results");
        assertThat(results).extracting(result -> result.get("symbol")).containsExactly("AAPL", "APP");
        assertThat(results.get(0)).containsEntry("id", aapl.intValue()).containsEntry("type", "STOCK");
        assertThat(results.get(1)).containsEntry("id", null);

        // Unknown at the provider
        json(postJson("/api/v1/investments/instruments/market", Map.of("symbol", "NOPE"), auth), 400);
        // Market instruments are shared and cannot be changed by a user
        json(
                putJson(
                        "/api/v1/investments/instruments/" + aapl,
                        Map.of(
                                "symbol",
                                "X",
                                "name",
                                "X",
                                "type",
                                "STOCK",
                                "currency",
                                "USD",
                                "valuation",
                                "MANUAL",
                                "manualPrice",
                                1),
                        auth),
                400);
        // An own instrument of another user is not found
        Long own = id(json(
                postJson(
                        "/api/v1/investments/instruments",
                        Map.of(
                                "symbol",
                                "GOLD",
                                "name",
                                "Gold",
                                "type",
                                "OTHER",
                                "currency",
                                "UAH",
                                "valuation",
                                "MANUAL",
                                "manualPrice",
                                4000),
                        other),
                201));
        json(get("/api/v1/investments/instruments/" + own).header("Authorization", auth), 404);
    }

    @Test
    void comparesTheValueOverTimeWithTheSameDepositsInABenchmark() throws Exception {
        json(
                postJson(
                        "/api/v1/transactions",
                        Map.of(
                                "type",
                                "TRANSFER",
                                "date",
                                "2030-03-10",
                                "accountId",
                                card,
                                "toAccountId",
                                broker,
                                "amount",
                                4000,
                                "toAmount",
                                100),
                        auth),
                201);
        Long aapl = apple();
        operation(trade("BUY", "2030-03-11", aapl, 1, 90, 0));
        prices.save(new InstrumentPrice(aapl, LocalDate.of(2030, 3, 11), new BigDecimal("90")));
        prices.save(new InstrumentPrice(aapl, LocalDate.of(2030, 3, 13), new BigDecimal("110")));
        when(provider.search("SPY"))
                .thenReturn(List.of(new Listing("SPY", "SPDR S&P 500", "NYSE", "ARCX", "United States", "USD", "ETF")));

        Map<String, Object> history = json(
                get("/api/v1/investments/history")
                        .param("currency", "USD")
                        .param("benchmark", "SP500")
                        .header("Authorization", auth),
                200);

        assertThat(history).containsEntry("currency", "USD").containsEntry("from", "2030-03-10");
        List<Map<String, Object>> points = (List<Map<String, Object>>) history.get("points");
        assertThat(points)
                .extracting(point -> point.get("date"))
                .hasSize(6)
                .first()
                .isEqualTo("2030-03-10");
        assertThat(points)
                .extracting(point -> number(point.get("value")))
                .containsExactly(100.0, 100.0, 100.0, 120.0, 120.0, 120.0);
        assertThat(points).extracting(point -> number(point.get("deposited"))).containsOnly(100.0);
        // The fund was just added: its prices are not there yet
        assertThat((Map<String, Object>) history.get("benchmark"))
                .containsEntry("symbol", "SPY")
                .containsEntry("pricesFrom", null);
        assertThat(points.get(0).get("benchmark")).isNull();

        Long spy = instruments.findMarketBySymbol("SPY").get(0).getId();
        assertThat(priceService.due(10)).contains(spy);
        prices.save(new InstrumentPrice(spy, LocalDate.of(2030, 3, 8), new BigDecimal("50")));
        prices.save(new InstrumentPrice(spy, LocalDate.of(2030, 3, 14), new BigDecimal("55")));
        history = json(
                get("/api/v1/investments/history")
                        .param("currency", "USD")
                        .param("benchmark", "SP500")
                        .header("Authorization", auth),
                200);
        points = (List<Map<String, Object>>) history.get("points");
        // 100 USD bought 2 units at 50
        assertThat(points)
                .extracting(point -> number(point.get("benchmark")))
                .containsExactly(100.0, 100.0, 100.0, 100.0, 110.0, 110.0);

        json(get("/api/v1/investments/history").param("from", "2031-01-01").header("Authorization", auth), 400);
    }

    private static LocalDate date(String value) {
        return LocalDate.parse(value);
    }

    @Test
    void buildsTheDividendCalendar() throws Exception {
        Long aapl = apple();
        operation(trade("BUY", "2029-01-10", aapl, 10, 100, 0));
        operation(Map.of(
                "type",
                "DIVIDEND",
                "accountId",
                broker,
                "instrumentId",
                aapl,
                "date",
                "2029-05-16",
                "amount",
                2.4,
                "tax",
                0.36));
        when(dividendProvider.enabled()).thenReturn(true);
        when(dividendProvider.dividends("AAPL"))
                .thenReturn(List.of(
                        new Dividend(date("2029-05-10"), date("2029-05-16"), null, null, new BigDecimal("0.24")),
                        new Dividend(date("2029-08-10"), date("2029-08-15"), null, null, new BigDecimal("0.25")),
                        new Dividend(
                                date("2030-04-10"),
                                date("2030-04-15"),
                                date("2030-03-01"),
                                null,
                                new BigDecimal("0.26"))));
        assertThat(dividendService.due(5)).containsExactly(aapl);
        dividendService.update(aapl);
        assertThat(dividendService.due(5)).isEmpty();

        Map<String, Object> calendar = json(
                get("/api/v1/investments/dividends").param("currency", "USD").header("Authorization", auth), 200);

        List<Map<String, Object>> events = (List<Map<String, Object>>) calendar.get("events");
        assertThat(events)
                .extracting(event -> event.get("date") + " " + event.get("status"))
                .containsExactly(
                        "2029-05-16 RECEIVED",
                        "2029-08-15 EXPECTED",
                        "2030-04-15 DECLARED",
                        "2030-05-16 FORECAST",
                        "2030-08-15 FORECAST");
        // 15 % withheld last time: the estimate of the next ones
        assertThat(events.get(2))
                .containsEntry("gross", 2.6)
                .containsEntry("tax", 0.39)
                .containsEntry("taxEstimated", true)
                .containsEntry("perShare", 0.26);
        assertThat(events.get(0)).containsEntry("net", 2.04).containsEntry("taxEstimated", false);
        assertThat(number(calendar.get("next12Months"))).isEqualTo(7.5);
        assertThat(number(calendar.get("next12MonthsNet"))).isEqualTo(6.38);
        assertThat(number(calendar.get("received12Months"))).isEqualTo(2.04);
        assertThat(number(calendar.get("yieldOnCost"))).isEqualTo(0.0075);
        List<Map<String, Object>> months = (List<Map<String, Object>>) calendar.get("months");
        assertThat(months.get(0)).containsEntry("month", "2029-03");
        assertThat(months)
                .filteredOn(month -> month.get("month").equals("2030-04"))
                .first()
                .satisfies(month -> assertThat(month).containsEntry("declared", 2.6));

        // A short range still has the 12-month totals
        Map<String, Object> april = json(
                get("/api/v1/investments/dividends")
                        .param("currency", "USD")
                        .param("from", "2030-04-01")
                        .param("to", "2030-04-30")
                        .header("Authorization", auth),
                200);
        assertThat((List<?>) april.get("events")).hasSize(1);
        assertThat(number(april.get("next12Months"))).isEqualTo(7.5);
        json(
                get("/api/v1/investments/dividends")
                        .param("from", "2020-01-01")
                        .param("to", "2030-01-01")
                        .header("Authorization", auth),
                400);
    }

    @Test
    void schedulesBondCoupons() throws Exception {
        Long uah = account("ICU", "INVESTMENT", "UAH", 0);
        Long id = id(json(
                postJson(
                        "/api/v1/investments/instruments",
                        Map.of(
                                "symbol", "UA4000227045",
                                "name", "OVDP",
                                "type", "GOVERNMENT_BOND",
                                "currency", "UAH",
                                "valuation", "NOMINAL",
                                "nominal", 1000,
                                "couponRate", 15.2,
                                "couponFrequency", 2,
                                "maturityDate", "2031-01-22"),
                        auth),
                201));
        operation(Map.of(
                "type",
                "BUY",
                "accountId",
                uah,
                "instrumentId",
                id,
                "date",
                "2029-10-01",
                "quantity",
                100,
                "price",
                1000));
        operation(Map.of("type", "COUPON", "accountId", uah, "instrumentId", id, "date", "2030-01-23", "amount", 7600));

        Map<String, Object> calendar = json(
                get("/api/v1/investments/dividends")
                        .param("accountId", uah.toString())
                        .header("Authorization", auth),
                200);

        List<Map<String, Object>> events = (List<Map<String, Object>>) calendar.get("events");
        // The January coupon is recorded; July and the next January are known from the schedule
        assertThat(events)
                .extracting(event -> event.get("date") + " " + event.get("status"))
                .containsExactly("2030-01-23 RECEIVED", "2030-07-22 DECLARED", "2031-01-22 DECLARED");
        assertThat(events.get(1)).containsEntry("gross", 7600.0).containsEntry("tax", 0.0);
        assertThat(number(calendar.get("next12Months"))).isEqualTo(15200);
    }

    @Test
    void rejectsUnboundedPeriodsAndImplausibleInstruments() throws Exception {
        json(get("/api/v1/investments/history").param("from", "1980-01-01").header("Authorization", auth), 400);
        Map<String, Object> tooLong = json(
                get("/api/v1/investments/history").param("from", "2000-01-01").header("Authorization", auth), 400);
        assertThat(tooLong.get("errors").toString()).contains("not be longer than 20 years");
        json(
                get("/api/v1/investments/dividends")
                        .param("from", "1900-01-01")
                        .param("to", "1901-01-01")
                        .header("Authorization", auth),
                400);
        Map<String, Object> bond = new HashMap<>(Map.of(
                "symbol", "UA1",
                "name", "Bond",
                "type", "GOVERNMENT_BOND",
                "currency", "UAH",
                "valuation", "NOMINAL",
                "nominal", 1000,
                "couponRate", 10,
                "couponFrequency", 2,
                "maturityDate", "2200-01-01"));
        json(postJson("/api/v1/investments/instruments", bond, auth), 400);
        bond.put("maturityDate", "2031-01-01");
        bond.put("couponFrequency", 5);
        json(postJson("/api/v1/investments/instruments", bond, auth), 400);
        bond.put("couponFrequency", 4);
        bond.put("manualPriceDate", "1900-01-01");
        json(postJson("/api/v1/investments/instruments", bond, auth), 400);

        Long aapl = apple();
        json(
                postJson(
                        "/api/v1/investments/operations",
                        Map.of(
                                "type",
                                "SPLIT",
                                "accountId",
                                broker,
                                "instrumentId",
                                aapl,
                                "date",
                                "2030-01-10",
                                "splitFrom",
                                1,
                                "splitTo",
                                2000),
                        auth),
                400);
        // A trade is in the currency of its instrument; income and fees are more than nothing
        Map<String, Object> inHryvnias = trade("BUY", "2030-01-10", aapl, 1, 100, 0);
        inHryvnias.put("currency", "UAH");
        json(postJson("/api/v1/investments/operations", inHryvnias, auth), 400);
        json(
                postJson(
                        "/api/v1/investments/operations",
                        Map.of(
                                "type",
                                "DIVIDEND",
                                "accountId",
                                broker,
                                "instrumentId",
                                aapl,
                                "date",
                                "2030-03-05",
                                "amount",
                                0),
                        auth),
                400);
        json(
                postJson(
                        "/api/v1/investments/operations",
                        Map.of("type", "FEE", "accountId", broker, "date", "2030-03-05", "amount", 0),
                        auth),
                400);
    }

    @Test
    void limitsAndCachesTheSearchesOfTheProvider() throws Exception {
        // One letter finds only own instruments
        json(get("/api/v1/investments/instruments/search").param("q", "a").header("Authorization", auth), 200);
        verify(provider, never()).search("a");

        json(get("/api/v1/investments/instruments/search").param("q", "apple").header("Authorization", auth), 200);
        json(get("/api/v1/investments/instruments/search").param("q", "Apple ").header("Authorization", auth), 200);
        verify(provider, times(1)).search(any());

        for (int i = 1; i < 30; i++) {
            json(
                    get("/api/v1/investments/instruments/search")
                            .param("q", "query" + i)
                            .header("Authorization", auth),
                    200);
        }
        json(
                get("/api/v1/investments/instruments/search")
                        .param("q", "one more")
                        .header("Authorization", auth),
                429);
        // Another user has a limit of their own
        json(
                get("/api/v1/investments/instruments/search")
                        .param("q", "one more")
                        .header("Authorization", other),
                200);
    }

    @Test
    void showsMarketInstrumentsOnlyToThoseWhoUseOrFoundThem() throws Exception {
        Long aapl = apple();
        json(get("/api/v1/investments/instruments/" + aapl).header("Authorization", auth), 200);
        json(get("/api/v1/investments/instruments/" + aapl).header("Authorization", other), 404);
        json(get("/api/v1/investments/instruments/" + aapl + "/prices").header("Authorization", other), 404);

        json(get("/api/v1/investments/instruments/search").param("q", "aapl").header("Authorization", other), 200);
        json(get("/api/v1/investments/instruments/" + aapl).header("Authorization", other), 200);
    }

    @Test
    void reloadsTheLastCloseAndBuysTheBenchmarkFromItsFirstClose() throws Exception {
        Long aapl = apple();
        operation(trade("BUY", "2030-03-11", aapl, 1, 90, 0));
        prices.save(new InstrumentPrice(aapl, LocalDate.of(2029, 1, 1), new BigDecimal("80")));
        prices.save(new InstrumentPrice(aapl, LocalDate.of(2030, 3, 14), new BigDecimal("100")));
        priceService.update(aapl);
        // The close of the 14th may have been taken during the session: loaded again
        verify(provider).closes("AAPL", "XNGS", LocalDate.of(2030, 3, 14), TODAY);

        json(
                postJson(
                        "/api/v1/transactions",
                        Map.of(
                                "type",
                                "TRANSFER",
                                "date",
                                "2030-03-10",
                                "accountId",
                                card,
                                "toAccountId",
                                broker,
                                "amount",
                                4000,
                                "toAmount",
                                100),
                        auth),
                201);
        when(provider.search("SPY"))
                .thenReturn(List.of(new Listing("SPY", "SPDR S&P 500", "NYSE", "ARCX", "United States", "USD", "ETF")));
        json(get("/api/v1/investments/history").param("benchmark", "SP500").header("Authorization", auth), 200);
        Long spy = instruments.findMarketBySymbol("SPY").get(0).getId();
        // The first close of the fund comes after the deposit: the deposit waits for it
        prices.save(new InstrumentPrice(spy, LocalDate.of(2030, 3, 12), new BigDecimal("50")));
        Map<String, Object> history = json(
                get("/api/v1/investments/history")
                        .param("currency", "USD")
                        .param("benchmark", "SP500")
                        .header("Authorization", auth),
                200);
        List<Map<String, Object>> points = (List<Map<String, Object>>) history.get("points");
        assertThat(points.get(0)).containsEntry("date", "2030-03-10").containsEntry("benchmark", null);
        assertThat(points.get(2)).containsEntry("date", "2030-03-12");
        assertThat(number(points.get(2).get("benchmark"))).isEqualTo(100);
    }
}
