package com.jerin.trading.screen;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * End-of-day market screen: pushed by ml-training/daily_screen.py after the close, read by the app's
 * Screen tab and stock pages. The payload is stored and served verbatim as JSON text.
 */
@RestController
@RequestMapping("/api/daily-screen")
public class DailyScreenController {

    // The payload is stored and served as opaque JSON text, so no JSON library is needed here - only the
    // session date is read, from the one key the builder always writes.
    private static final Pattern SESSION_DATE = Pattern.compile("\"sessionDate\"\\s*:\\s*\"(\\d{4}-\\d{2}-\\d{2})\"");

    private final DailyScreenRepository repository;

    public DailyScreenController(DailyScreenRepository repository) {
        this.repository = repository;
    }

    /** Upserts by the payload's own sessionDate (yyyy-MM-dd). */
    @PostMapping
    public Map<String, Object> push(@RequestBody String body) {
        Matcher m = SESSION_DATE.matcher(body);
        if (!body.trim().startsWith("{") || !m.find()) {
            throw new IllegalArgumentException("payload must be a JSON object with a sessionDate");
        }
        LocalDate date = LocalDate.parse(m.group(1));
        DailyScreen row = repository.findByScreenDate(date)
                .orElseGet(() -> DailyScreen.builder().screenDate(date).build());
        row.setPayload(body);
        row.setCreatedAt(OffsetDateTime.now());
        repository.save(row);
        return Map.of("sessionDate", date.toString(), "bytes", body.length());
    }

    @GetMapping("/latest")
    public ResponseEntity<String> latest() {
        return repository.findAllByOrderByScreenDateDesc(PageRequest.of(0, 1)).stream().findFirst()
                .map(r -> json(r.getPayload()))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/{date}")
    public ResponseEntity<String> forDate(@PathVariable String date) {
        return repository.findByScreenDate(LocalDate.parse(date)).map(r -> json(r.getPayload()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Newest first; used by the daily job to score earlier hypotheses and carry the running tally. */
    @GetMapping("/recent")
    public ResponseEntity<String> recent(@RequestParam(defaultValue = "5") int limit) {
        List<DailyScreen> rows = repository.findAllByOrderByScreenDateDesc(PageRequest.of(0, Math.max(1, Math.min(limit, 30))));
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(rows.get(i).getPayload());
        }
        return json(sb.append(']').toString());
    }

    private static ResponseEntity<String> json(String body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
