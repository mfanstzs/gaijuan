#!/bin/sh
set -eu
umask 077
cd /opt/deskgrade
directory=/opt/deskgrade/backups
mkdir -p "$directory"
timestamp=$(date -u +%Y%m%dT%H%M%SZ)
target="$directory/deskgrade-$timestamp.sql.gz"
temporary="$directory/.deskgrade-$timestamp.sql.partial"
# A failed dump is never published as a valid backup.
trap 'rm -f -- "$temporary"' EXIT HUP INT TERM
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --single-transaction --no-tablespaces --set-gtid-purged=OFF --hex-blob --databases deskgrade -u root' > "$temporary"
test -s "$temporary"
gzip -c "$temporary" > "$target"
gzip -t "$target"
sha256sum "$target" > "$target.sha256"
configuration="$directory/deskgrade-config-$timestamp.tar.gz"
tar -czf "$configuration" /etc/deskgrade /opt/deskgrade/compose.yaml /opt/deskgrade/.env 2>/dev/null
tar -tzf "$configuration" >/dev/null
sha256sum "$configuration" > "$configuration.sha256"
# Retention is intentionally manual until an operator sets a documented policy.
printf 'Backup completed: %s\n' "$target"
