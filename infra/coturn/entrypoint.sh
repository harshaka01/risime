#!/bin/sh
# Appends the shared secret from the environment to a private copy of the config, so the secret
# is never in the repo or on the process command line. Extra arguments go to turnserver.
set -eu
: "${TURN_SECRET:?TURN_SECRET must be set (repo .env)}"
umask 077
conf=/tmp/turnserver.conf
cp /etc/coturn/turnserver.conf "$conf"
printf 'static-auth-secret=%s\n' "$TURN_SECRET" >> "$conf"
unset TURN_SECRET
exec turnserver -c "$conf" "$@"
