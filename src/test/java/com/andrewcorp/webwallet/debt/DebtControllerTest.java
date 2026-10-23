package com.andrewcorp.webwallet.debt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.currency.ExchangeRate;
import com.andrewcorp.webwallet.currency.ExchangeRateRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DebtControllerTest extends AbstractIntegrationTest {

    @Autowired
    private ExchangeRateRepository rates;

    private String auth;
    private Long card;
    private Long debts;

    @BeforeEach
    void setUp() throws Exception {
        auth = loginNewUser();
        card = createAccount(auth, "Card", "CARD", "UAH", 1000);
        debts = createAccount(auth, "Debts", "DEBT", "UAH", 0);
    }

    private void transfer(String date, Long from, Long to, double amount, String payee) throws Exception {
        transfer(date, from, to, amount, amount, payee);
    }

    private void transfer(String date, Long from, Long to, double amount, double toAmount, String payee)
            throws Exception {
        Map<String, Object> body = new HashMap<>(
                Map.of("type", "TRANSFER", "date", date, "accountId", from, "amount", amount, "toAccountId", to));
        body.put("toAmount", toAmount);
        body.put("payeeName", payee);
        json(postJson("/api/v1/transactions", body, auth), 201);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> people() throws Exception {
        Map<String, Object> response = json(get("/api/v1/debts").header("Authorization", auth), 200);
        assertThat(response).containsEntry("currency", "UAH").containsEntry("accountId", debts.intValue());
        return (List<Map<String, Object>>) response.get("people");
    }

    @Test
    void aPersonOwesWhatWasGivenMinusWhatWasReceived() throws Exception {
        transfer("2026-10-01", card, debts, 1000, "Олег");
        transfer("2026-10-05", debts, card, 300, "Олег");
        transfer("2026-10-07", debts, card, 500, "Іра");
        transfer("2026-10-08", card, debts, 20, null);

        List<Map<String, Object>> people = people();

        assertThat(people).hasSize(3);
        // The biggest balances first
        assertThat(people.get(0))
                .containsEntry("name", "Олег")
                .containsEntry("lastDate", "2026-10-05")
                .containsEntry("operations", 2);
        assertThat(new BigDecimal(people.get(0).get("balance").toString())).isEqualByComparingTo("700");
        assertThat(people.get(1)).containsEntry("name", "Іра");
        assertThat(new BigDecimal(people.get(1).get("balance").toString())).isEqualByComparingTo("-500");
        // Operations without a payee are grouped together
        assertThat(people.get(2)).containsEntry("payeeId", null).containsEntry("name", null);
        assertThat(new BigDecimal(people.get(2).get("balance").toString())).isEqualByComparingTo("20");
    }

    @Test
    void amountsAreConvertedAtTheRateOfTheDay() throws Exception {
        rates.save(new ExchangeRate("USD", LocalDate.of(2026, 10, 7), new BigDecimal("41.5")));
        Long dollarDebts = createAccount(auth, "Debts USD", "DEBT", "USD", 0);
        // What arrived in the debt account counts, in its currency
        transfer("2026-10-07", card, dollarDebts, 420, 10, "Олег");

        List<Map<String, Object>> people = people();

        assertThat(new BigDecimal(people.get(0).get("balance").toString())).isEqualByComparingTo("415");
    }

    @Test
    void aUserWithoutDebtsHasNoDebtAccountAndNobody() throws Exception {
        Map<String, Object> response = json(get("/api/v1/debts").header("Authorization", loginNewUser()), 200);

        assertThat(response).containsEntry("accountId", null);
        assertThat((List<?>) response.get("people")).isEmpty();
    }
}
