package com.jerin.trading.commodity;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCX commodity report: pushed by ml-training/commodity_report.py after each MCX session, read by the app's Commodities
 * tab. The payload is stored and served verbatim as JSON text (only its reportDate is read).
 */
@RestController
@RequestMapping("/api/commodities")
public class CommodityReportController {

    private static final Pattern REPORT_DATE = Pattern.compile("\"reportDate\"\\s*:\\s*\"(\\d{4}-\\d{2}-\\d{2})\"");

    private final CommodityReportRepository repository;

    public CommodityReportController(CommodityReportRepository repository) {
        this.repository = repository;
    }

    /** Upserts by the payload's own reportDate (yyyy-MM-dd): a second run on the same date replaces the first. */
    @PostMapping("/report")
    public Map<String, Object> push(@RequestBody String body) {
        Matcher m = REPORT_DATE.matcher(body);
        if (!body.trim().startsWith("{") || !m.find()) {
            throw new IllegalArgumentException("payload must be a JSON object with a reportDate");
        }
        LocalDate date = LocalDate.parse(m.group(1));
        CommodityReport row = repository.findByReportDate(date)
                .orElseGet(() -> CommodityReport.builder().reportDate(date).build());
        row.setPayload(body);
        row.setCreatedAt(OffsetDateTime.now());
        repository.save(row);
        return Map.of("reportDate", date.toString(), "bytes", body.length());
    }

    @GetMapping("/latest")
    public ResponseEntity<String> latest() {
        return repository.findAllByOrderByReportDateDesc(PageRequest.of(0, 1)).stream().findFirst()
                .map(r -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(r.getPayload()))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
