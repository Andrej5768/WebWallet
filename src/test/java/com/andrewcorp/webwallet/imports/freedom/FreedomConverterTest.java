package com.andrewcorp.webwallet.imports.freedom;

import static com.andrewcorp.webwallet.imports.freedom.FreedomReports.at;
import static com.andrewcorp.webwallet.imports.freedom.FreedomReports.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.imports.TestXlsx;
import com.andrewcorp.webwallet.imports.broker.BrokerStatement;
import com.andrewcorp.webwallet.imports.broker.BrokerStatement.BrokerOperation;
import com.andrewcorp.webwallet.imports.broker.BrokerStatement.BrokerTicker;
import com.andrewcorp.webwallet.imports.broker.BrokerStatement.Skip;
import com.andrewcorp.webwallet.investment.OperationType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class FreedomConverterTest {

    private final FreedomReportParser parser = new FreedomReportParser();

    private BrokerStatement statement(byte[]... files) {
        return FreedomConverter.convert(parser.parse(List.of(files)));
    }

    private static Map<String, BrokerOperation> byKey(BrokerStatement statement) {
        return statement.operations().stream().collect(Collectors.toMap(BrokerOperation::key, Function.identity()));
    }

    @Test
    void readsBothReportsInAnyOrder() {
        FreedomReport report = parser.parse(List.of(FreedomReports.cashFlows(), FreedomReports.trades()));

        assertThat(report.trades()).hasSize(5);
        assertThat(report.trades().get(0).time()).isEqualTo(at(2, 1, 10));
        assertThat(report.trades().get(0).buy()).isTrue();
        assertThat(report.trades().get(1).buy()).isFalse();
        assertThat(report.cashFlows()).hasSize(13);
        assertThat(report.cashFlows().get(1).kind()).isEqualTo("Negative cash balance fee");
        assertThat(report.cashFlows().get(1).amount()).isEqualByComparingTo("-0.1");
    }

    @Test
    void turnsTheReportsIntoOperations() {
        BrokerStatement statement = statement(FreedomReports.trades(), FreedomReports.cashFlows());
        Map<String, BrokerOperation> operations = byKey(statement);

        assertThat(statement.broker()).isEqualTo("Freedom Finance");
        assertThat(statement.currency()).isEqualTo("USD");
        // Oldest first
        assertThat(statement.operations().get(0).key()).isEqualTo("cash:1");
        assertThat(statement.tickers())
                .containsExactly(
                        new BrokerTicker("SCHD.US", "SCHD", "US", "USD"),
                        new BrokerTicker("FXUS", "FXUS", "RU", "RUB"));

        // The fee of the cash flows is the fee of the trade
        BrokerOperation buy = operations.get("trade:101");
        assertThat(buy.type()).isEqualTo(OperationType.BUY);
        assertThat(buy.ticker()).isEqualTo("SCHD.US");
        assertThat(buy.amount()).isEqualByComparingTo("250");
        assertThat(buy.fee()).isEqualByComparingTo("2");
        assertThat(buy.currency()).isEqualTo("USD");
        assertThat(operations.get("trade:105").type()).isEqualTo(OperationType.SELL);

        // Selling euros for dollars, buying roubles for dollars
        BrokerOperation euros = operations.get("trade:102");
        assertThat(euros.type()).isEqualTo(OperationType.EXCHANGE);
        assertThat(euros.currency()).isEqualTo("EUR");
        assertThat(euros.amount()).isEqualByComparingTo("100");
        assertThat(euros.toCurrency()).isEqualTo("USD");
        assertThat(euros.toAmount()).isEqualByComparingTo("110");
        BrokerOperation roubles = operations.get("trade:104");
        assertThat(roubles.currency()).isEqualTo("USD");
        assertThat(roubles.amount()).isEqualByComparingTo("3.75");
        assertThat(roubles.toCurrency()).isEqualTo("RUB");
        assertThat(roubles.toAmount()).isEqualByComparingTo("300");

        // A trade in roubles with its fee charged in dollars: the fee is an operation of its own
        BrokerOperation fxus = operations.get("trade:103");
        assertThat(fxus.currency()).isEqualTo("RUB");
        assertThat(fxus.fee()).isEqualByComparingTo("0");
        assertThat(operations.get("cash:4").type()).isEqualTo(OperationType.FEE);
        assertThat(operations.get("cash:4").amount()).isEqualByComparingTo("0.2");

        // The tax names the record date differently from the dividend
        BrokerOperation dividend = operations.get("cash:6");
        assertThat(dividend.type()).isEqualTo(OperationType.DIVIDEND);
        assertThat(dividend.ticker()).isEqualTo("SCHD.US");
        assertThat(dividend.amount()).isEqualByComparingTo("1.5");
        assertThat(dividend.tax()).isEqualByComparingTo("0.23");
        assertThat(operations).doesNotContainKeys("cash:3", "cash:5", "cash:7");
        assertThat(statement.merged()).isEqualTo(3);

        assertThat(operations.get("cash:1").type()).isEqualTo(OperationType.DEPOSIT);
        assertThat(operations.get("cash:2").currency()).isEqualTo("EUR");
        assertThat(operations.get("cash:10").type()).isEqualTo(OperationType.WITHDRAWAL);
        assertThat(operations.get("cash:10").amount()).isEqualByComparingTo("50");
        assertThat(operations.get("cash:11").type()).isEqualTo(OperationType.FEE);
        assertThat(operations.get("cash:12").type()).isEqualTo(OperationType.FEE);
        assertThat(operations.get("cash:8").skip()).isEqualTo(Skip.SERVICE);
        assertThat(operations.get("cash:9").skip()).isEqualTo(Skip.SERVICE);
        assertThat(operations.get("cash:13").skip()).isEqualTo(Skip.UNKNOWN);

        Map<String, BigDecimal> cash = new TreeMap<>();
        for (BrokerOperation operation : statement.operations()) {
            if (operation.type() != null) {
                cash.merge(
                        operation.currency(),
                        operation.type().cash(operation.amount(), operation.fee(), operation.tax()),
                        BigDecimal::add);
                if (operation.toCurrency() != null) {
                    cash.merge(operation.toCurrency(), operation.toAmount(), BigDecimal::add);
                }
            }
        }
        assertThat(cash.get("USD")).isEqualByComparingTo("416.22");
        assertThat(cash.get("EUR")).isEqualByComparingTo("0");
        assertThat(cash.get("RUB")).isEqualByComparingTo("0");
    }

    @Test
    void takesTheFeesOfTheTradesReportWithoutTheCashFlows() {
        BrokerStatement statement = statement(FreedomReports.trades());

        assertThat(byKey(statement).get("trade:101").fee()).isEqualByComparingTo("2");
        // Without the cash flows a trade on the Moscow exchange is taken to be in roubles
        assertThat(byKey(statement).get("trade:103").currency()).isEqualTo("RUB");
        assertThat(statement.merged()).isZero();
    }

    @Test
    void keepsFeesOfTradesMissingFromTheTradesReport() {
        BrokerStatement statement = statement(FreedomReports.cashFlows());

        assertThat(byKey(statement).get("cash:3").type()).isEqualTo(OperationType.FEE);
        assertThat(byKey(statement).get("cash:3").amount()).isEqualByComparingTo("2");
    }

    @Test
    void rejectsOtherFiles() {
        byte[] other = TestXlsx.of(List.of(row("Date", "Payee", "Sum"), row(at(1, 1, 0), "Shop", 5)));

        assertThatThrownBy(() -> parser.parse(List.of(other)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.violations().get(0).messageKey()).isEqualTo("error.import.brokerFile"));
        assertThatThrownBy(() -> parser.parse(List.of(FreedomReports.trades(), FreedomReports.trades())))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.violations().get(0).messageKey()).isEqualTo("error.import.brokerFileTwice"));
        assertThatThrownBy(() -> parser.parse(List.of(new byte[] {1, 2, 3}))).isInstanceOf(ApiException.class);
    }
}
