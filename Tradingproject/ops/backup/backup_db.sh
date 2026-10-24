#!/bin/bash
# Nightly backup of the IRREPLACEABLE database tables to the private repo JerinBenito/jTrading-backups.
# Runs on the app server as user opc (systemd timer: trading-backup.timer). Candles and daily features are NOT backed up -
# they can be re-downloaded from Upstox; these tables are the live record that cannot be re-created:
# AI predictions and per-call snapshots, deterministic forecasts, signals and outcomes, order-book / option-chain snapshots,
# daily screens, remaining-swing forecasts and a few small settings tables.
#
# The database login is read from the RUNNING APP's environment (/proc/<pid>/environ) so no password is copied into this
# script or the repo. Keeps the last 14 daily dumps plus one dump per month; if the git history grows past ~150 MB it is
# squashed to a single commit so the repo stays small. On success writes a timestamp the watchdog reads (/api/ops/backup-status).
set -euo pipefail

REPO_DIR=/home/opc/jTrading-backups
STATUS_DIR=/home/opc/backups
DAY=$(date +%F)
TABLES=(ai_predictions ai_prediction_snapshots hourly_predictions signal_predictions signal_outcomes pattern_stats
        range_calibration_state market_depth_snapshots option_chain_snapshot daily_screens intraday_risk_forecasts
        global_market_snapshot results_calendar company_fundamental)

mkdir -p "$STATUS_DIR"
PID=$(pgrep -f 'signal-backend.*jar' | head -1)
[ -n "$PID" ] || { echo "app process not found - cannot read the database settings" >&2; exit 1; }
getenv() { tr '\0' '\n' < "/proc/$PID/environ" | grep "^$1=" | head -1 | cut -d= -f2-; }
export PGPASSWORD="$(getenv DB_PASSWORD)"
DBH="$(getenv DB_HOST)"; DBP="$(getenv DB_PORT)"; DBN="$(getenv DB_NAME)"; DBU="$(getenv DB_USER)"

cd "$REPO_DIR"
git pull --rebase --autostash -q || true
mkdir -p daily monthly

ARGS=(); for t in "${TABLES[@]}"; do ARGS+=(-t "$t"); done
pg_dump -h "$DBH" -p "$DBP" -U "$DBU" -d "$DBN" "${ARGS[@]}" --no-owner --no-privileges | gzip -9 > "daily/$DAY.sql.gz.tmp"
[ "$(stat -c %s "daily/$DAY.sql.gz.tmp")" -gt 1000 ] || { echo "dump suspiciously small" >&2; exit 1; }
mv "daily/$DAY.sql.gz.tmp" "daily/$DAY.sql.gz"

# row counts per table, so a restore can be checked against the live system
: > "daily/$DAY.counts.txt"
for t in "${TABLES[@]}"; do
  printf '%s %s\n' "$t" "$(psql -h "$DBH" -p "$DBP" -U "$DBU" -d "$DBN" -Atc "select count(*) from $t")" >> "daily/$DAY.counts.txt"
done

[ "$(date +%d)" = "01" ] && cp "daily/$DAY.sql.gz" "monthly/$(date +%Y-%m).sql.gz" || true
# retention: newest 14 daily dumps (and their count files)
ls -1 daily/*.sql.gz | head -n -14 | while read -r f; do rm -f "$f" "${f%.sql.gz}.counts.txt"; done

git add -A
git -c user.name="jTrading backup" -c user.email="backup@jtrading.invalid" commit -q -m "backup $DAY" || true
if [ "$(du -sm .git | cut -f1)" -gt 150 ]; then            # keep the repo small: squash history
  git checkout -q --orphan squashed
  git add -A
  git -c user.name="jTrading backup" -c user.email="backup@jtrading.invalid" commit -q -m "backup $DAY (history squashed)"
  git branch -M main
  git push -q -f origin main
  git reflog expire --expire=now --all && git gc -q --prune=now
else
  git push -q origin HEAD:main
fi
date -u +%FT%TZ > "$STATUS_DIR/last-success.txt"
echo "backup $DAY ok: $(stat -c %s "daily/$DAY.sql.gz") bytes"
