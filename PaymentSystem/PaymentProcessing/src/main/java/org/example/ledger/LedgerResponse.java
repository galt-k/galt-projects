package org.example.ledger;

import java.util.Objects;

public record LedgerResponse(LedgerStatus status, String entryId, String code, String message) {
    public LedgerResponse {
        Objects.requireNonNull(status, "status");
        if (status == LedgerStatus.SUCCESS && (entryId == null || entryId.isBlank())) {
            throw new IllegalArgumentException("A successful ledger response requires an entry ID");
        }
    }
}
