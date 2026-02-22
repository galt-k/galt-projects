package com.rev.triage.ragservice.dto;

/**
 * Request body for trace ingestion.
 *
 * @param lookback  how far back to fetch traces (e.g., "1h", "6h", "1d"). Default: "1h"
 * @param limit     max traces per service. Default: 20
 */
public record IngestTracesRequest(
    String lookback,
    Integer limit
) {
    public String effectiveLookback() {
        return lookback != null && !lookback.isBlank() ? lookback : "1h";
    }

    public int effectiveLimit() {
        return limit != null && limit > 0 ? limit : 20;
    }
}
