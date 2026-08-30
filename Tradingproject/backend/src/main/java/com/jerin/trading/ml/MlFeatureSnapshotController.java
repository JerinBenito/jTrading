package com.jerin.trading.ml;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Manual triggers + read access for the ML groundwork dataset (see {@link MlFeatureSnapshotService}).
 * Not wired into the live hourly job yet — deliberately manual for now, until it's clear how often
 * this actually needs refreshing once real model-training work starts.
 */
@RestController
@RequestMapping("/api/ml/features")
public class MlFeatureSnapshotController {

    private final MlFeatureSnapshotService snapshotService;
    private final MlFeatureSnapshotRepository snapshotRepository;

    public MlFeatureSnapshotController(MlFeatureSnapshotService snapshotService, MlFeatureSnapshotRepository snapshotRepository) {
        this.snapshotService = snapshotService;
        this.snapshotRepository = snapshotRepository;
    }

    /** Recomputes and upserts every day's feature row for one instrument (NIFTY/BANKNIFTY or any basket symbol). */
    @PostMapping("/{instrument}/backfill")
    public MlFeatureSnapshotResult backfillOne(@PathVariable String instrument) {
        return snapshotService.computeAndSave(instrument);
    }

    /** Same, for NIFTY + BANKNIFTY + the full NIFTY 50 basket in one call. */
    @PostMapping("/backfill-all")
    public Map<String, Object> backfillAll() {
        List<MlFeatureSnapshotResult> results = snapshotService.backfillAll();
        int totalRows = results.stream().mapToInt(MlFeatureSnapshotResult::rowsWritten).sum();
        return Map.of("instrumentsProcessed", results.size(), "totalRowsWritten", totalRows, "results", results);
    }

    /** Read-only — the stored dataset for one instrument, oldest first. */
    @GetMapping("/{instrument}")
    public List<MlFeatureSnapshot> get(@PathVariable String instrument) {
        return snapshotRepository.findByInstrumentOrderByTradingDateAsc(instrument);
    }
}
