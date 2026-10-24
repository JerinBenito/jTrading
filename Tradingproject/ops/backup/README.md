# Database backup

Nightly (02:30 IST) the app server dumps the **irreplaceable** database tables and pushes them to the private repo
`JerinBenito/jTrading-backups`. Candles and daily features are not backed up - they can be re-downloaded from Upstox
(`/api/ingestion/.../backfill`, `/api/ml/features/backfill-all`).

| Piece | Where |
|---|---|
| Script | `/home/opc/backup_db.sh` (source: `ops/backup/backup_db.sh`) |
| Schedule | systemd timer `trading-backup.timer` (21:00 UTC) -> `trading-backup.service` |
| Output | `daily/YYYY-MM-DD.sql.gz` + `daily/YYYY-MM-DD.counts.txt` (last 14 kept) and `monthly/YYYY-MM.sql.gz` |
| Git access | deploy key `~/.ssh/jtrading_backup_key` on the server (write access to the backup repo only) |
| Alarm | `/api/ops/backup-status` -> the Health Watchdog workflow fails (email) if no success in 36 h |

Tables: ai_predictions, ai_prediction_snapshots, hourly_predictions, signal_predictions, signal_outcomes, pattern_stats,
range_calibration_state, market_depth_snapshots, option_chain_snapshot, daily_screens, intraday_risk_forecasts,
global_market_snapshot, results_calendar, company_fundamental.

## Check on the server
```bash
systemctl list-timers trading-backup.timer
sudo journalctl -u trading-backup.service -n 30 --no-pager
sudo systemctl start trading-backup.service      # run one now
```

## Restore test (do this occasionally - an untested backup is not a backup)
On any machine with PostgreSQL 16+ and access to the private repo:
```bash
git clone git@github.com:JerinBenito/jTrading-backups.git && cd jTrading-backups/daily
createdb restore_test
gunzip -c 2026-10-24.sql.gz | psql -d restore_test
for t in $(cut -d' ' -f1 2026-10-24.counts.txt); do echo "$t $(psql -d restore_test -Atc "select count(*) from $t")"; done > /tmp/restored.txt
diff <(sort 2026-10-24.counts.txt) <(sort /tmp/restored.txt) && echo "RESTORE MATCHES"
dropdb restore_test
```
The dump contains only table data/structure for the tables above (no passwords). To restore into the live database after a loss,
run the same `gunzip | psql` against the new database after the app's Flyway migrations have created the empty tables
(use `--data-only` dumps or drop the tables first, depending on the situation).
