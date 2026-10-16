#!/usr/bin/env bash
# Daily outreach run. Install on an always-on server (the CRM in state/ must persist:
# losing it means emailing the same people again).
#   crontab -e
#   17 14 * * 1-5 /opt/x20-music/deploy/run-daily.sh >> /opt/x20-music/state/daily.log 2>&1
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; source deploy/.env; set +a
mkdir -p state out
cp -n state/crm.sqlite "state/backup-$(date +%F).sqlite" 2>/dev/null || true
python3 -m x20music daily --settings config/settings.json --db state/crm.sqlite --out out --days 3
