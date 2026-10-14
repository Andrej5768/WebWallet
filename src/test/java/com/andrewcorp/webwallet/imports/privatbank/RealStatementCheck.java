package com.andrewcorp.webwallet.imports.privatbank;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Checks the parser against a real statement on a developer machine; prints only a summary without personal data.
 * Not run by the build (the class name does not end with Test). Usage:
 * {@code mvn test -Dtest=RealStatementCheck -Dstatement=path/to/statement.pdf -Djacoco.skip=true}
 */
class RealStatementCheck {

    @Test
    @EnabledIfSystemProperty(named = "statement", matches = ".+")
    void summary() throws Exception {
        String text = new PdfText().extract(Files.readAllBytes(Path.of(System.getProperty("statement"))));
        PrivatStatement statement = new PrivatStatementParser().parse(text);

        System.out.println("=== SUMMARY ===");
        System.out.println("records=" + statement.records().size());
        System.out.println("warnings=" + statement.warnings());
        Map<String, Integer> categories = new TreeMap<>();
        statement.records().forEach(r -> categories.merge(r.bankCategory().length() > 30
                ? r.bankCategory().substring(0, 30) : r.bankCategory(), 1, Integer::sum));
        System.out.println("bankCategories=" + categories);
        long foreign = statement.records().stream().filter(r -> !r.operationCurrency().equals(statement.currency())).count();
        long noCard = statement.records().stream().filter(r -> r.card() == null).count();
        long cards = statement.records().stream().map(PrivatStatement.Record::card).filter(c -> c != null).distinct().count();
        long withFee = statement.records().stream().filter(r -> r.fee().signum() != 0).count();
        System.out.println("foreignCurrency=" + foreign + " withoutCard=" + noCard + " distinctCards=" + cards
                + " withFee=" + withFee);
        System.out.println("=== END ===");
    }
}
