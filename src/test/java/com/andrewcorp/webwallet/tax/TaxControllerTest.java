package com.andrewcorp.webwallet.tax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.TestClock;
import com.andrewcorp.webwallet.common.util.Zones;
import com.andrewcorp.webwallet.currency.ExchangeRate;
import com.andrewcorp.webwallet.currency.ExchangeRateRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@SuppressWarnings("unchecked")
class TaxControllerTest extends AbstractIntegrationTest {

    @Autowired
    private ExchangeRateRepository rates;

    private String auth;
    private Long fopUah;
    private Long fopUsd;
    private Long card;
    private Long freelance;
    private Long gifts;

    @BeforeEach
    void setUp() throws Exception {
        auth = loginNewUser();
        // The access token is checked against the real time, so sign in before moving the clock
        TestClock.INSTANCE.set(LocalDate.of(2030, 12, 31)
                .atTime(LocalTime.NOON)
                .atZone(Zones.KYIV)
                .toInstant());
        rates.save(new ExchangeRate("USD", LocalDate.of(2030, 1, 1), new BigDecimal("40")));
        fopUah = account("ФОП UAH", "FOP", "UAH");
        fopUsd = account("ФОП USD", "FOP", "USD");
        card = account("Card", "CARD", "UAH");
        freelance = category("Freelance", "INCOME", null);
        gifts = category("Gifts", "INCOME", null);
    }

    private Long account(String name, String type, String currency) throws Exception {
        return id(json(
                postJson(
                        "/api/v1/accounts",
                        Map.of("name", name, "type", type, "currency", currency, "openingBalance", 0),
                        auth),
                201));
    }

    private Long category(String name, String kind, Long parentId) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("name", name, "kind", kind));
        body.put("parentId", parentId);
        return id(json(postJson("/api/v1/categories", body, auth), 201));
    }

    private Long transaction(String type, String date, Long account, double amount, Long category) throws Exception {
        Map<String, Object> body =
                new HashMap<>(Map.of("type", type, "date", date, "accountId", account, "amount", amount));
        if (category != null) {
            body.put("categoryId", category);
        }
        return id(json(postJson("/api/v1/transactions", body, auth), 201));
    }

    private Map<String, Object> report() throws Exception {
        return json(get("/api/v1/taxes/fop/2030").header("Authorization", auth), 200);
    }

    /** For answers that are not an object. */
    private void perform(org.springframework.test.web.servlet.RequestBuilder request, int status) throws Exception {
        mockMvc.perform(request)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .is(status));
    }

    private static double number(Object value) {
        return ((Number) value).doubleValue();
    }

    @Test
    void countsTheIncomeOfTheSoleProprietorByQuarter() throws Exception {
        transaction("INCOME", "2030-02-10", fopUah, 10000, null);
        transaction("INCOME", "2030-05-15", fopUsd, 100, null);
        Long subcategory = category("Design", "INCOME", freelance);
        transaction("INCOME", "2030-08-01", card, 3000, subcategory);
        transaction("INCOME", "2030-08-02", card, 500, gifts);
        Long refund = transaction("INCOME", "2030-08-20", fopUah, 1200, null);
        Long added = transaction("INCOME", "2030-11-01", card, 700, gifts);
        json(
                postJson(
                        "/api/v1/transactions",
                        Map.of(
                                "type",
                                "TRANSFER",
                                "date",
                                "2030-03-01",
                                "accountId",
                                card,
                                "toAccountId",
                                fopUah,
                                "amount",
                                2000),
                        auth),
                201);
        perform(putJson("/api/v1/taxes/income-categories", Map.of("categoryIds", List.of(freelance)), auth), 200);
        json(putJson("/api/v1/taxes/transactions/" + refund, Map.of("included", false), auth), 204);
        json(putJson("/api/v1/taxes/transactions/" + added, Map.of("included", true), auth), 204);
        json(putJson("/api/v1/taxes/fop/2030/quarters/1", Map.of("paid", true), auth), 204);

        Map<String, Object> report = report();

        assertThat((List<Object>) report.get("fopAccountIds")).hasSize(2);
        List<Map<String, Object>> quarters = (List<Map<String, Object>>) report.get("quarters");
        // 100 USD at 40; the design income of a subcategory of the chosen one; the gift added by hand
        assertThat(quarters)
                .extracting(quarter -> number(quarter.get("income")))
                .containsExactly(10000.0, 4000.0, 3000.0, 700.0);
        assertThat(quarters)
                .extracting(quarter -> number(quarter.get("singleTax")))
                .containsExactly(500.0, 200.0, 150.0, 35.0);
        assertThat(quarters)
                .extracting(quarter -> number(quarter.get("military")))
                .containsExactly(100.0, 40.0, 30.0, 7.0);
        // 22 % of the minimum wage of 8 647 a month
        assertThat(number(quarters.get(0).get("esv"))).isEqualTo(5707.02);
        assertThat(quarters.get(0))
                .containsEntry("paid", true)
                .containsEntry("declarationDue", "2030-05-10")
                .containsEntry("taxDue", "2030-05-20")
                // 20 April 2030 is a Saturday
                .containsEntry("esvDue", "2030-04-22");
        assertThat(quarters.get(1)).containsEntry("paid", false);
        // 9 November is a Saturday: the declaration moves to Monday and the payment follows it
        assertThat(quarters.get(2))
                .containsEntry("declarationDue", "2030-11-11")
                .containsEntry("taxDue", "2030-11-21");
        Map<String, Object> totals = (Map<String, Object>) report.get("totals");
        assertThat(number(totals.get("income"))).isEqualTo(17700);
        assertThat(number(report.get("incomeLimit"))).isEqualTo(1167 * 8647.0);

        Map<String, Map<String, Object>> incomes = ((List<Map<String, Object>>) report.get("incomes"))
                .stream().collect(Collectors.toMap(income -> (String) income.get("date"), Function.identity()));
        assertThat(incomes).containsOnlyKeys("2030-02-10", "2030-05-15", "2030-08-01", "2030-08-20", "2030-11-01");
        assertThat(incomes.get("2030-05-15")).containsEntry("reason", "ACCOUNT").containsEntry("currency", "USD");
        assertThat(number(incomes.get("2030-05-15").get("rate"))).isEqualTo(40);
        assertThat(number(incomes.get("2030-05-15").get("amountUah"))).isEqualTo(4000);
        assertThat(incomes.get("2030-08-01")).containsEntry("reason", "CATEGORY");
        assertThat(incomes.get("2030-08-20")).containsEntry("included", false).containsEntry("reason", "ACCOUNT");
        assertThat(incomes.get("2030-11-01")).containsEntry("reason", "MANUAL");

        // The marks can be forgotten
        json(putJson("/api/v1/taxes/transactions/" + refund, new HashMap<String, Object>(), auth), 204);
        assertThat(number(((List<Map<String, Object>>) report().get("quarters"))
                        .get(2)
                        .get("income")))
                .isEqualTo(4200);
    }

    @Test
    void appliesTheUsersOwnRates() throws Exception {
        transaction("INCOME", "2030-02-10", fopUah, 10000, null);

        Map<String, Object> defaults = json(get("/api/v1/taxes/settings/2030").header("Authorization", auth), 200);
        assertThat((Map<String, Object>) defaults.get("values"))
                .containsEntry("fopGroup", 3)
                .containsEntry("esvEnabled", true);
        assertThat(number(((Map<String, Object>) defaults.get("values")).get("pdfoRate")))
                .isEqualTo(18);
        assertThat(((Map<String, Object>) defaults.get("sources"))
                        .get("taxCode")
                        .toString())
                .startsWith("https://zakon.rada.gov.ua");

        Map<String, Object> changed = json(
                putJson(
                        "/api/v1/taxes/settings/2030",
                        Map.of("fopMilitaryRate", 0, "esvEnabled", false, "singleTaxRate", 3),
                        auth),
                200);
        assertThat(number(((Map<String, Object>) changed.get("values")).get("singleTaxRate")))
                .isEqualTo(3);
        // The defaults stay, to show what was changed
        assertThat(number(((Map<String, Object>) changed.get("defaults")).get("singleTaxRate")))
                .isEqualTo(5);
        Map<String, Object> quarter = ((List<Map<String, Object>>) report().get("quarters")).get(0);
        assertThat(number(quarter.get("singleTax"))).isEqualTo(300);
        assertThat(number(quarter.get("military"))).isZero();
        assertThat(number(quarter.get("esv"))).isZero();

        // Group 2 pays a fixed amount a month: 20 % of the minimum wage
        json(putJson("/api/v1/taxes/settings/2030", Map.of("fopGroup", 2), auth), 200);
        quarter = ((List<Map<String, Object>>) report().get("quarters")).get(0);
        assertThat(number(quarter.get("singleTax"))).isEqualTo(5188.2);
        assertThat(quarter).containsEntry("declarationDue", null).containsEntry("taxDue", "2030-01-21");

        // Other years keep their own values
        Map<String, Object> other = json(get("/api/v1/taxes/settings/2029").header("Authorization", auth), 200);
        assertThat((Map<String, Object>) other.get("values")).containsEntry("fopGroup", 3);
    }

    @Test
    void rejectsWhatIsNotTheUsers() throws Exception {
        Long expense = category("Food", "EXPENSE", null);
        perform(putJson("/api/v1/taxes/income-categories", Map.of("categoryIds", List.of(expense)), auth), 400);
        json(putJson("/api/v1/taxes/settings/2030", Map.of("fopGroup", 4), auth), 400);
        json(get("/api/v1/taxes/fop/1999").header("Authorization", auth), 400);

        Long income = transaction("INCOME", "2030-02-10", fopUah, 100, null);
        String other = loginNewUser();
        json(putJson("/api/v1/taxes/transactions/" + income, Map.of("included", false), other), 404);
        perform(putJson("/api/v1/taxes/income-categories", Map.of("categoryIds", List.of(freelance)), other), 400);
    }

    private Long investmentOperation(Map<String, Object> body) throws Exception {
        return id(json(postJson("/api/v1/investments/operations", body, auth), 201));
    }

    @Test
    void taxesTheInvestmentIncomeInHryvniasWithTheLossCarriedOver() throws Exception {
        rates.save(new ExchangeRate("USD", LocalDate.of(2028, 1, 1), new BigDecimal("30")));
        Long broker = account("Broker", "INVESTMENT", "USD");
        Long stock = id(json(
                postJson(
                        "/api/v1/investments/instruments",
                        Map.of(
                                "symbol",
                                "ACME",
                                "name",
                                "Acme",
                                "type",
                                "STOCK",
                                "currency",
                                "USD",
                                "valuation",
                                "MANUAL",
                                "manualPrice",
                                100),
                        auth),
                201));
        Map<String, Object> trade = new HashMap<>(Map.of("accountId", broker, "instrumentId", stock));
        trade.putAll(Map.of("type", "BUY", "date", "2028-06-01", "quantity", 10, "price", 100));
        investmentOperation(new HashMap<>(trade));
        trade.putAll(Map.of("type", "SELL", "date", "2029-03-01", "quantity", 4, "price", 90));
        investmentOperation(new HashMap<>(trade));
        trade.putAll(Map.of("type", "SELL", "date", "2030-03-01", "quantity", 6, "price", 110));
        investmentOperation(new HashMap<>(trade));
        Long dividend = investmentOperation(Map.of(
                "type",
                "DIVIDEND",
                "accountId",
                broker,
                "instrumentId",
                stock,
                "date",
                "2030-06-01",
                "amount",
                10,
                "tax",
                1.5));

        // 360 USD at 30 for lots bought at 30 for 400: a loss of 1 200 carried over
        Map<String, Object> before = json(get("/api/v1/taxes/investments/2029").header("Authorization", auth), 200);
        assertThat(number(before.get("profit"))).isEqualTo(-1200);
        assertThat(number(before.get("taxableProfit"))).isZero();
        assertThat(number(before.get("carriedOut"))).isEqualTo(1200);

        Map<String, Object> report = json(get("/api/v1/taxes/investments/2030").header("Authorization", auth), 200);
        Map<String, Object> sale = ((List<Map<String, Object>>) report.get("sales")).get(0);
        // 660 USD at 40 against 600 USD bought at 30: 60 USD, but 8 400 UAH
        assertThat(number(sale.get("incomeUah"))).isEqualTo(26400);
        assertThat(number(sale.get("costUah"))).isEqualTo(18000);
        assertThat(number(sale.get("resultUah"))).isEqualTo(8400);
        assertThat(number(sale.get("resultInCurrency"))).isEqualTo(60);
        assertThat(number(report.get("carriedIn"))).isEqualTo(1200);
        assertThat(number(report.get("taxableProfit"))).isEqualTo(7200);
        assertThat(number(report.get("carriedOut"))).isZero();

        Map<String, Object> income = ((List<Map<String, Object>>) report.get("dividends")).get(0);
        assertThat(number(income.get("amountUah"))).isEqualTo(400);
        assertThat(number(income.get("withheldUah"))).isEqualTo(60);
        // Without the document the tax withheld abroad does not count
        assertThat(income).containsEntry("document", false).containsEntry("documentSet", false);
        assertThat(number(income.get("pdfo"))).isEqualTo(72);
        assertThat(number(income.get("military"))).isEqualTo(20);
        // 72 + 18 % of 7 200; 20 + 5 % of 7 200
        assertThat(number(report.get("pdfo"))).isEqualTo(1368);
        assertThat(number(report.get("military"))).isEqualTo(380);
        assertThat(report).containsEntry("declarationDue", "2031-05-01").containsEntry("paymentDue", "2031-07-31");

        json(putJson("/api/v1/taxes/investments/dividends/" + dividend, Map.of("document", true), auth), 204);
        report = json(get("/api/v1/taxes/investments/2030").header("Authorization", auth), 200);
        assertThat(number(report.get("creditUah"))).isEqualTo(60);
        assertThat(number(report.get("pdfo"))).isEqualTo(1308);
        json(putJson("/api/v1/taxes/investments/dividends/" + dividend, Map.of("document", true), loginNewUser()), 404);
    }
}
