package org.example.ledger;

@FunctionalInterface
public interface LedgerClient {
    /** Repeated attempts must use the same saved transactionId and unchanged financial parameters. */
    LedgerResponse postEntry(LedgerRequest request) throws LedgerException;
}
