#!/usr/bin/env bash
#
# Sends a build to a Telegram chat through a bot, so a new APK can be installed straight from the
# phone without a cable or a download from a browser.
#
#   TELEGRAM_BOT_TOKEN=... TELEGRAM_CHAT_ID=... scripts/ci/send-telegram.sh <file> [caption]
#
# Credentials come from the environment and are never echoed: the token is a bearer credential that
# grants full control of the bot, and a build log is a public artefact.

set -euo pipefail

FILE=${1:?usage: send-telegram.sh <file> [caption]}
CAPTION=${2:-}

: "${TELEGRAM_BOT_TOKEN:?TELEGRAM_BOT_TOKEN is not set}"
: "${TELEGRAM_CHAT_ID:?TELEGRAM_CHAT_ID is not set}"

[ -f "$FILE" ] || { echo "::error::No such file: $FILE"; exit 1; }

# Telegram refuses documents above 50 MB from a bot; a release APK is well under, but a debug one
# with every dex intact is not far off, and the failure is otherwise a bare HTTP 413.
SIZE_MB=$(( $(stat -c%s "$FILE") / 1024 / 1024 ))
if [ "$SIZE_MB" -ge 50 ]; then
    echo "::error::$FILE is ${SIZE_MB}MB; Telegram bots cannot send more than 50MB"
    exit 1
fi

echo "Sending $(basename "$FILE") (${SIZE_MB}MB) to Telegram..."
RESPONSE=$(curl -sS --fail-with-body \
    -F "chat_id=${TELEGRAM_CHAT_ID}" \
    -F "document=@${FILE}" \
    -F "caption=${CAPTION}" \
    -F "parse_mode=HTML" \
    "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendDocument" 2>&1) || {
        # The response can quote the request, so it is filtered rather than printed: a token in a
        # log is a token that has to be revoked.
        echo "::error::Telegram rejected the upload: $(sed "s|${TELEGRAM_BOT_TOKEN}|***|g" <<< "$RESPONSE" | head -c 400)"
        exit 1
    }

if grep -q '"ok":true' <<< "$RESPONSE"; then
    echo "Sent."
else
    echo "::error::Unexpected response: $(sed "s|${TELEGRAM_BOT_TOKEN}|***|g" <<< "$RESPONSE" | head -c 400)"
    exit 1
fi
