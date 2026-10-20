package com.andrewcorp.webwallet.imports.freedom;

import static com.andrewcorp.webwallet.imports.freedom.FreedomReports.at;
import static com.andrewcorp.webwallet.imports.freedom.FreedomReports.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.TestClock;
import com.andrewcorp.webwallet.common.util.Zones;
import com.andrewcorp.webwallet.currency.ExchangeRate;
import com.andrewcorp.webwallet.currency.ExchangeRateRepository;
import com.andrewcorp.webwallet.investment.market.MarketDataProvider;
import com.andrewcorp.webwallet.investment.market.MarketDataProvider.Listing;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

@SuppressWarnings("unchecked")
class FreedomImportTest extends AbstractIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2030, 3, 15);
    private static final Listing SCHD =
            new Listing("SCHD", "Schwab US Dividend Equity ETF", "NYSE ARCA", "ARCX", "United States", "USD", "ETF");

    @MockitoBean
    private MarketDataProvider provider;

    @Autowired
    private ExchangeRateRepository rates;

    private String auth;

    @BeforeEach
    void setUp() throws Exception {
        auth = loginNewUser();
        TestClock.INSTANCE.set(TODAY.atTime(LocalTime.NOON).atZone(Zones.KYIV).toInstant());
        rates.save(new ExchangeRate("USD", LocalDate.of(2030, 1, 1), new BigDecimal("40")));
        rates.save(new ExchangeRate("EUR", LocalDate.of(2030, 1, 1), new BigDecimal("44")));
        when(provider.enabled()).thenReturn(true);
        when(provider.search(any())).thenReturn(List.of());
        when(provider.search("SCHD")).thenReturn(List.of(SCHD));
    }

    private MockMultipartHttpServletRequestBuilder preview(byte[]... files) {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/v1/imports/freedom/preview");
        for (int i = 0; i < files.length; i++) {
            request.file(new MockMultipartFile(
                    "files",
                    "report" + i + ".xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    files[i]));
        }
        request.header("Authorization", auth);
        return request;
    }

    private static Map<String, Map<String, Object>> byKey(List<Map<String, Object>> list, String key) {
        return list.stream().collect(Collectors.toMap(item -> (String) item.get(key), Function.identity()));
    }

    private static double number(Object value) {
        return ((Number) value).doubleValue();
    }

    @Test
    void previewsAndImportsTheReports() throws Exception {
        Map<String, Object> preview = json(preview(FreedomReports.trades(), FreedomReports.cashFlows()), 200);

        assertThat((Map<String, Object>) preview.get("account"))
                .containsEntry("id", null)
                .containsEntry("name", "Freedom Finance")
                .containsEntry("currency", "USD");
        assertThat(preview).containsEntry("from", "2030-01-10").containsEntry("to", "2030-03-05");
        assertThat(preview).containsEntry("merged", 3).containsEntry("providerUnavailable", false);

        Map<String, Map<String, Object>> tickers =
                byKey((List<Map<String, Object>>) preview.get("instruments"), "ticker");
        Map<String, Object> schd = (Map<String, Object>) tickers.get("SCHD.US").get("suggestion");
        assertThat(schd)
                .containsEntry("symbol", "SCHD")
                .containsEntry("micCode", "ARCX")
                .containsEntry("manual", false);
        assertThat(number(tickers.get("SCHD.US").get("quantityAfter"))).isEqualTo(6);
        assertThat((Map<String, Object>) tickers.get("FXUS").get("suggestion"))
                .containsEntry("manual", true)
                .containsEntry("currency", "RUB");
        assertThat(tickers.get("FXUS")).containsEntry("shortfall", false);

        List<Map<String, Object>> rows = (List<Map<String, Object>>) preview.get("operations");
        assertThat(rows).hasSize(15);
        // Newest first
        assertThat(rows.get(0)).containsEntry("key", "cash:13").containsEntry("status", "SKIPPED");
        Map<String, Map<String, Object>> operations = byKey(rows, "key");
        assertThat(operations.get("cash:8")).containsEntry("status", "SKIPPED").containsEntry("skip", "SERVICE");
        assertThat(operations.get("trade:101")).containsEntry("status", "NEW").containsEntry("type", "BUY");
        assertThat(number(operations.get("trade:101").get("cash"))).isEqualTo(-252);

        Map<String, Map<String, Object>> cash = byKey((List<Map<String, Object>>) preview.get("cash"), "currency");
        assertThat(number(cash.get("USD").get("amount"))).isEqualTo(416.22);
        assertThat(number(cash.get("EUR").get("amount"))).isZero();
        assertThat(number(cash.get("RUB").get("amount"))).isZero();

        // Nothing is saved by the preview
        List<Map<String, Object>> accounts = jsonMapper.readValue(
                mockMvc.perform(get("/api/v1/accounts").header("Authorization", auth))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                List.class);
        assertThat(accounts).noneMatch(account -> account.get("name").equals("Freedom Finance"));
        // The ledger previews do not show it
        json(get("/api/v1/imports/previews/" + preview.get("id")).header("Authorization", auth), 404);

        Map<String, Object> result = json(
                postJson(
                        "/api/v1/imports/freedom/previews/" + preview.get("id") + "/confirm",
                        Map.of("excluded", List.of("cash:12")),
                        auth),
                200);
        assertThat(result)
                .containsEntry("accountCreated", true)
                .containsEntry("instrumentsCreated", 1)
                .containsEntry("operationsImported", 11)
                .containsEntry("duplicates", 0);
        Long account = ((Number) result.get("accountId")).longValue();

        Map<String, Object> portfolio = json(
                get("/api/v1/investments/portfolio")
                        .param("accountId", account.toString())
                        .param("currency", "USD")
                        .header("Authorization", auth),
                200);
        // 500 USD and 100 EUR (44 / 40 = 1.1 USD) brought, 50 USD taken
        assertThat(number(portfolio.get("deposited"))).isEqualTo(560);
        assertThat(number(portfolio.get("dividendsLast12Months"))).isEqualTo(1.27);
        assertThat((List<Map<String, Object>>) portfolio.get("positions")).hasSize(2);
        Map<String, Map<String, Object>> money = byKey((List<Map<String, Object>>) portfolio.get("cash"), "currency");
        // The negative balance fee was left out
        assertThat(number(money.get("USD").get("amount"))).isEqualTo(416.32);

        // The preview is gone; the same reports again are all duplicates
        json(postJson("/api/v1/imports/freedom/previews/" + preview.get("id") + "/confirm", Map.of(), auth), 404);
        Map<String, Object> again = json(preview(FreedomReports.cashFlows(), FreedomReports.trades()), 200);
        assertThat(((Map<String, Object>) again.get("account")).get("id")).isEqualTo(account.intValue());
        Map<String, Map<String, Object>> repeated = byKey((List<Map<String, Object>>) again.get("operations"), "key");
        assertThat(repeated.get("trade:101")).containsEntry("status", "DUPLICATE");
        // What was left out comes now
        assertThat(repeated.get("cash:12")).containsEntry("status", "NEW");
        Map<String, Map<String, Object>> known = byKey((List<Map<String, Object>>) again.get("instruments"), "ticker");
        assertThat(((Map<String, Object>) known.get("FXUS").get("suggestion")).get("instrumentId"))
                .isNotNull();
        Map<String, Object> second =
                json(postJson("/api/v1/imports/freedom/previews/" + again.get("id") + "/confirm", Map.of(), auth), 200);
        assertThat(second)
                .containsEntry("accountCreated", false)
                .containsEntry("instrumentsCreated", 0)
                .containsEntry("operationsImported", 1)
                .containsEntry("duplicates", 11);
    }

    @Test
    void rejectsSalesWithoutTheirPurchases() throws Exception {
        byte[] trades = FreedomReports.trades(
                List.of(row(201L, at(2, 1, 10), at(2, 2, 0), "SCHD.US", "Sell", 3, 25, 75, 0, 2)));

        Map<String, Object> preview = json(preview(trades), 200);

        Map<String, Object> schd = ((List<Map<String, Object>>) preview.get("instruments")).get(0);
        assertThat(schd).containsEntry("shortfall", true);
        Map<String, Object> error = json(
                postJson("/api/v1/imports/freedom/previews/" + preview.get("id") + "/confirm", Map.of(), auth), 400);
        assertThat(error.toString()).contains("SCHD.US");
    }

    @Test
    void choosesAnotherInstrumentAndRejectsOneInAnotherCurrency() throws Exception {
        Map<String, Object> preview = json(preview(FreedomReports.trades()), 200);

        Map<String, Object> wrong = json(
                postJson(
                        "/api/v1/imports/freedom/previews/" + preview.get("id") + "/confirm",
                        Map.of("instruments", List.of(Map.of("ticker", "FXUS", "symbol", "SCHD", "micCode", "ARCX"))),
                        auth),
                400);
        assertThat(wrong.toString()).contains("FXUS");

        Map<String, Object> result = json(
                postJson(
                        "/api/v1/imports/freedom/previews/" + preview.get("id") + "/confirm",
                        Map.of("instruments", List.of(Map.of("ticker", "SCHD.US", "manual", true))),
                        auth),
                200);
        assertThat(result).containsEntry("instrumentsCreated", 2);
    }

    @Test
    void rejectsOtherFiles() throws Exception {
        json(preview(new byte[] {1, 2, 3}), 400);
        json(preview(FreedomReports.trades(), FreedomReports.trades()), 400);
        json(preview(FreedomReports.trades(), FreedomReports.cashFlows(), FreedomReports.trades()), 400);
    }
}
