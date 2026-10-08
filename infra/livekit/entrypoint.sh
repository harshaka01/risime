#!/bin/sh
# Writes the LiveKit API key file and the coturn shared secret from the environment into a private
# tmpfs (mode 600), then starts livekit-server with the repo config. No secret is in the repo, on
# the process command line or in a log. Extra arguments go to livekit-server.
set -eu
: "${LIVEKIT_API_KEY:?LIVEKIT_API_KEY must be set (repo .env)}"
: "${LIVEKIT_API_SECRET:?LIVEKIT_API_SECRET must be set (repo .env)}"
: "${TURN_SECRET:?TURN_SECRET must be set (repo .env)}"
umask 077
mkdir -p /tmp/livekit
printf '%s: %s\n' "$LIVEKIT_API_KEY" "$LIVEKIT_API_SECRET" > /tmp/livekit/keys.yaml
printf '%s\n' "$TURN_SECRET" > /tmp/livekit/turn-secret
unset LIVEKIT_API_KEY LIVEKIT_API_SECRET TURN_SECRET
exec /livekit-server --config "${LIVEKIT_CONFIG:-/etc/livekit/livekit.yaml}" "$@"
