#!/bin/sh
# Brings the server to the images CI built from master: the backend, the web client and the Caddy config.
# Run as root by webwallet-deploy.service, after the service has updated the checkout to origin/master.
set -eu
cd "$(dirname "$0")/.."

backend_image=ghcr.io/andrej5768/webwallet:master
client_image=ghcr.io/andrej5768/webwalletclient:master
site=/srv/webwallet
state=/var/lib/webwallet-deploy
compose="docker compose -f docker-compose.yml -f deploy/docker-compose.prod.yml"
mkdir -p "$state"

image_id() {
    docker image inspect -f '{{.Id}}' "$1" 2>/dev/null || true
}

# Backend: a dump before a new image runs its migrations
before=$(image_id "$backend_image")
docker pull -q "$backend_image" >/dev/null
if [ -n "$before" ] && [ "$before" != "$(image_id "$backend_image")" ]; then
    echo "New backend image, dumping the database"
    sh deploy/backup-db.sh pre-deploy
fi
# Mailpit catches the emails until a real mail server is set
if grep -q '^MAIL_HOST=.' .env; then
    $compose up -d --no-build postgres backend
    $compose rm -sf mailpit >/dev/null 2>&1
else
    $compose up -d --no-build postgres mailpit backend
fi

# Web client: files of the image replace the site; old assets stay for pages opened before the deploy
client=$(docker pull -q "$client_image" >/dev/null && image_id "$client_image")
if [ "$client" != "$(cat "$state/client-image" 2>/dev/null)" ]; then
    echo "New web client $client"
    new=$(mktemp -d "$site.new.XXXXXX")
    container=$(docker create "$client_image")
    docker cp "$container:/site/." "$new/" >/dev/null
    docker rm -f "$container" >/dev/null
    if [ -d "$site/assets" ]; then
        cp -r --update=none "$site/assets/." "$new/assets/"
    fi
    chown -R root:root "$new" && chmod -R u=rwX,go=rX "$new"
    rm -rf "$site.old"
    [ -d "$site" ] && mv "$site" "$site.old"
    mv "$new" "$site"
    rm -rf "$site.old"
    echo "$client" > "$state/client-image"
fi

if ! cmp -s deploy/Caddyfile /etc/caddy/Caddyfile; then
    echo "New Caddy config"
    caddy validate --adapter caddyfile --config deploy/Caddyfile
    cp deploy/Caddyfile /etc/caddy/Caddyfile
    systemctl reload caddy
fi

docker image prune -f >/dev/null
