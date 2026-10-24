package com.jerin.trading.ops;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Operational status for the GitHub watchdog. /backup-status reports when the nightly database backup last SUCCEEDED
 * (ops/backup/backup_db.sh writes a timestamp file on success), so a silently failing backup raises an alarm.
 */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private final Path statusFile;

    public OpsController(@Value("${ops.backup-status-file:/home/opc/backups/last-success.txt}") String statusFile) {
        this.statusFile = Path.of(statusFile);
    }

    @GetMapping("/backup-status")
    public Map<String, Object> backupStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            String text = Files.readString(statusFile).strip();
            Instant last = Instant.parse(text);
            out.put("lastSuccess", text);
            out.put("ageHours", Math.round(Duration.between(last, Instant.now()).toMinutes() / 6.0) / 10.0);
        } catch (Exception e) {
            out.put("lastSuccess", null);
            out.put("ageHours", null);
        }
        return out;
    }
}
