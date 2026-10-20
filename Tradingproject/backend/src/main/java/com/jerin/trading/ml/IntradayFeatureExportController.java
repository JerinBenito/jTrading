package com.jerin.trading.ml;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;

/** Read-only export of raw intraday feature+outcome rows for offline model training/evaluation. See {@link IntradayFeatureExportService}. */
@RestController
@RequestMapping("/api/ml/intraday-features")
public class IntradayFeatureExportController {

    private final IntradayFeatureExportService exportService;
    private final ObjectMapper mapper;

    /**
     * Only one full export at a time. The pooled export is ~190,000 rows and growing; two overlapping ones
     * (the hourly AI runs, a slow client, a manual call) on a 400 MB heap are what exhausted memory on
     * 2026-10-19 and killed Tomcat's acceptor thread, taking the whole API down for two days.
     */
    private final Semaphore fullExportSlot = new Semaphore(1);

    public IntradayFeatureExportController(IntradayFeatureExportService exportService, ObjectMapper mapper) {
        this.exportService = exportService;
        this.mapper = mapper;
    }

    @GetMapping("/{instrument}")
    public List<IntradayFeatureRow> export(@PathVariable String instrument) {
        return exportService.export(instrument);
    }

    /**
     * Pooled across NIFTY + BANKNIFTY + the NIFTY 50 basket - much larger training set. Streamed one instrument at
     * a time (peak memory ~ one instrument's rows, about 1 MB, instead of the whole 190,000-row list); the output is
     * the same single JSON array as before.
     */
    @GetMapping(value = "/all", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StreamingResponseBody> exportAll() {
        StreamingResponseBody body = out -> {
            fullExportSlot.acquireUninterruptibly();
            try {
                out.write('[');
                boolean first = true;
                for (String tag : exportService.exportTags()) {
                    List<IntradayFeatureRow> rows = exportService.export(tag);
                    if (rows.isEmpty()) {
                        continue;
                    }
                    String json = mapper.writeValueAsString(rows);          // "[{...},{...}]"
                    if (!first) {
                        out.write(',');
                    }
                    out.write(json.substring(1, json.length() - 1).getBytes(StandardCharsets.UTF_8));
                    first = false;
                    out.flush();
                }
                out.write(']');
            } finally {
                fullExportSlot.release();
            }
        };
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    /** Today's current (in-progress) feature state, for live inference - not backtesting. */
    @GetMapping("/{instrument}/live")
    public Optional<LiveFeatureSnapshot> live(@PathVariable String instrument) {
        return exportService.liveFeatures(instrument);
    }
}
