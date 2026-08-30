package com.jerin.trading.ml;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only export of raw intraday feature+outcome rows for offline model training/evaluation. See {@link IntradayFeatureExportService}. */
@RestController
@RequestMapping("/api/ml/intraday-features")
public class IntradayFeatureExportController {

    private final IntradayFeatureExportService exportService;

    public IntradayFeatureExportController(IntradayFeatureExportService exportService) {
        this.exportService = exportService;
    }

    @GetMapping("/{instrument}")
    public List<IntradayFeatureRow> export(@PathVariable String instrument) {
        return exportService.export(instrument);
    }

    /** Pooled across NIFTY + BANKNIFTY + the NIFTY 50 basket — much larger training set. */
    @GetMapping("/all")
    public List<IntradayFeatureRow> exportAll() {
        return exportService.exportAll();
    }
}
