#!/bin/sh
# Dumps the database into $BACKUP_DIR and keeps the dumps for 14 days. The first argument is a prefix of the file name.
set -eu
cd "$(dirname "$0")/.."
backup_dir=${BACKUP_DIR:-$HOME/backups}
umask 077
mkdir -p "$backup_dir"
docker compose exec -T postgres pg_dump -U webwallet webwallet | gzip > "$backup_dir/${1:-db}-$(date +%F-%H%M).sql.gz"
find "$backup_dir" -name '*.sql.gz' -mtime +14 -delete
