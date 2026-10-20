package com.andrewcorp.webwallet.imports.preview;

/** Where the operations of an import preview come from. */
public enum ImportSource {
    PRIVATBANK,
    ZENMONEY,
    /** Broker reports of Freedom Finance: investment operations, previewed by the broker import. */
    FREEDOM;

    /** Transactions of the ledger, previewed and confirmed by {@link ImportPreviewService}. */
    public boolean ledger() {
        return this != FREEDOM;
    }
}
