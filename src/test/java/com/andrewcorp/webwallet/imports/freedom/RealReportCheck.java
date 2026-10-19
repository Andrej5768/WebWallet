package com.andrewcorp.webwallet.imports.freedom;

import com.andrewcorp.webwallet.imports.broker.BrokerStatement;
import com.andrewcorp.webwallet.imports.broker.BrokerStatement.BrokerOperation;
import com.andrewcorp.webwallet.investment.OperationType;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Checks the import against real Freedom Finance reports on a developer machine; prints only totals. Not run by the
 * build (the class name does not end with Test). Usage:
 * {@code mvn test -Dtest=RealReportCheck -Dreports=trades.xlsx,cash.xlsx -Djacoco.skip=true}
 */
class RealReportCheck {

    @Test
    @EnabledIfSystemProperty(named = "reports", matches = ".+")
    void summary() throws Exception {
        List<byte[]> files = new ArrayList<>();
        for (String path : System.getProperty("reports").split(",")) {
            files.add(Files.readAllBytes(Path.of(path.trim())));
        }
        FreedomReport report = new FreedomReportParser().parse(files);
        BrokerStatement statement = FreedomConverter.convert(report);

        Map<String, Integer> types = new TreeMap<>();
        Map<String, BigDecimal> cash = new TreeMap<>();
        Map<String, BigDecimal> held = new TreeMap<>();
        for (BrokerOperation operation : statement.operations()) {
            String type = operation.type() != null ? operation.type().name() : "SKIP_" + operation.skip();
            types.merge(type, 1, Integer::sum);
            if (operation.type() == null) {
                continue;
            }
            cash.merge(
                    operation.currency(),
                    operation.type().cash(operation.amount(), operation.fee(), operation.tax()),
                    BigDecimal::add);
            if (operation.type() == OperationType.EXCHANGE) {
                cash.merge(operation.toCurrency(), operation.toAmount(), BigDecimal::add);
            }
            if (operation.type() == OperationType.BUY) {
                held.merge(operation.ticker(), operation.quantity(), BigDecimal::add);
            } else if (operation.type() == OperationType.SELL) {
                held.merge(operation.ticker(), operation.quantity().negate(), BigDecimal::add);
            }
        }
        System.out.println("=== SUMMARY ===");
        System.out.println("trades=" + report.trades().size() + " cashFlows="
                + report.cashFlows().size());
        System.out.println("types=" + types + " merged=" + statement.merged() + " currency=" + statement.currency());
        System.out.println("tickers=" + statement.tickers());
        System.out.println("cash=" + cash);
        System.out.println("held=" + held);
        System.out.println("=== END ===");
    }
}
