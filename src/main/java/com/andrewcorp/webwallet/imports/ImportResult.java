package com.andrewcorp.webwallet.imports;

import java.util.ArrayList;
import java.util.List;

/**
 * Summary of an import.
 *
 * @param transactionsSkipped already imported earlier or deleted in the source
 * @param transactionsMatched existing transactions (entered manually or imported from elsewhere) recognized as the
 *                            imported operations instead of creating duplicates
 * @param budgetsImported     monthly envelope assignments
 * @param warnings            records that could not be imported as is, at most {@value #MAX_WARNINGS}
 */
public record ImportResult(
        int accountsCreated,
        int accountsUpdated,
        int categoriesCreated,
        int payeesCreated,
        int transactionsImported,
        int transactionsSkipped,
        int transactionsMatched,
        int budgetsImported,
        List<String> warnings) {

    public static final int MAX_WARNINGS = 100;

    /** Mutable counters filled during an import. */
    public static class Builder {

        public int accountsCreated;
        public int accountsUpdated;
        public int categoriesCreated;
        public int payeesCreated;
        public int transactionsImported;
        public int transactionsSkipped;
        public int transactionsMatched;
        public int budgetsImported;
        private final List<String> warnings = new ArrayList<>();

        public void warn(String warning) {
            if (warnings.size() < MAX_WARNINGS) {
                warnings.add(warning);
            }
        }

        public ImportResult build() {
            return new ImportResult(accountsCreated, accountsUpdated, categoriesCreated, payeesCreated,
                    transactionsImported, transactionsSkipped, transactionsMatched, budgetsImported, List.copyOf(warnings));
        }
    }
}
