package com.rev.triage.ragservice.config;

import com.rev.triage.ragservice.service.IngestionService;
import com.rev.triage.ragservice.service.TelemetryIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Handles automatic ingestion of documentation and traces.
 *
 * On startup:
 *   - Documents ingested synchronously (fast, local classpath files)
 *   - Traces ingested asynchronously (Jaeger may not be ready yet)
 *
 * Continuously:
 *   - Scheduled job polls Jaeger every 5 minutes for new traces
 *   - This ensures new traces are automatically embedded into ChromaDB
 *
 * Manual endpoints (POST /ingest, POST /ingest/traces) still work for on-demand ingestion.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class StartupIngestionConfig {

    private static final Logger log = LoggerFactory.getLogger(StartupIngestionConfig.class);

    private final IngestionService ingestionService;
    private final TelemetryIngestionService telemetryIngestionService;

    @Value("${rag.startup.ingest-docs:true}")
    private boolean ingestDocsOnStartup;

    @Value("${rag.startup.ingest-traces:true}")
    private boolean ingestTracesOnStartup;

    @Value("${rag.startup.traces-lookback:1h}")
    private String tracesLookback;

    @Value("${rag.startup.traces-limit:50}")
    private int tracesLimit;

    @Value("${rag.schedule.trace-polling-enabled:true}")
    private boolean tracePollingEnabled;

    @Value("${rag.schedule.trace-polling-lookback:10m}")
    private String pollingLookback;

    @Value("${rag.schedule.trace-polling-limit:20}")
    private int pollingLimit;

    public StartupIngestionConfig(IngestionService ingestionService,
                                   TelemetryIngestionService telemetryIngestionService) {
        this.ingestionService = ingestionService;
        this.telemetryIngestionService = telemetryIngestionService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        // Ingest docs synchronously — clear old docs first to prevent duplicates across restarts
        if (ingestDocsOnStartup) {
            try {
                log.info("Auto-ingesting project documentation on startup...");
                ingestionService.clearExistingDocuments();
                int docCount = ingestionService.ingestDocuments();
                log.info("Startup: ingested {} documents", docCount);
            } catch (Exception e) {
                log.warn("Startup doc ingestion failed (non-fatal): {}", e.getMessage());
            }
        }

        // Ingest traces asynchronously — Jaeger may not be up yet, or may have no traces
        if (ingestTracesOnStartup) {
            ingestTracesAsync();
        }
    }

    @Async
    public void ingestTracesAsync() {
        try {
            // Small delay to give Jaeger time to be ready
            Thread.sleep(5000);
            log.info("Auto-ingesting traces from Jaeger (lookback: {}, limit: {})...",
                    tracesLookback, tracesLimit);
            int traceCount = telemetryIngestionService.ingestTraces(tracesLookback, tracesLimit);
            log.info("Startup: ingested {} traces", traceCount);
        } catch (Exception e) {
            log.warn("Startup trace ingestion failed (non-fatal): {}. " +
                    "Jaeger may not be running. Use POST /ingest/traces to retry.", e.getMessage());
        }
    }

    /**
     * Poll Jaeger every 5 minutes for new traces and ingest them.
     * Only looks back 10 minutes to pick up recent traces.
     * Deduplication is handled by TelemetryIngestionService (in-memory + ChromaDB removeAll).
     */
    @Scheduled(fixedDelayString = "${rag.schedule.trace-polling-interval:300000}",
               initialDelay = 60000)  // first poll 1 min after startup
    public void pollTracesFromJaeger() {
        if (!tracePollingEnabled) {
            return;
        }
        try {
            int count = telemetryIngestionService.ingestTraces(pollingLookback, pollingLimit);
            if (count > 0) {
                log.info("Scheduled trace poll: ingested {} new traces (lookback: {})",
                        count, pollingLookback);
            }
        } catch (Exception e) {
            log.debug("Scheduled trace poll failed: {}", e.getMessage());
        }
    }
}
