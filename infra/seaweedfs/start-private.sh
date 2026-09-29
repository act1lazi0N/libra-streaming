#!/bin/sh
set -eu
umask 077
# Keep credentials out of weed mini's environment-identity logging path.
case "${MEDIA_STORAGE_ACCESS_KEY:-}" in ''|*[!A-Za-z0-9_+=/-]*) echo 'STORAGE_CREDENTIAL_CONFIGURATION_INVALID' >&2; exit 1;; esac
case "${MEDIA_STORAGE_SECRET_KEY:-}" in ''|*[!A-Za-z0-9_+=/-]*) echo 'STORAGE_CREDENTIAL_CONFIGURATION_INVALID' >&2; exit 1;; esac
if [ "${#MEDIA_STORAGE_ACCESS_KEY}" -lt 3 ] || [ "${#MEDIA_STORAGE_ACCESS_KEY}" -gt 128 ] ||
   [ "${#MEDIA_STORAGE_SECRET_KEY}" -lt 16 ] || [ "${#MEDIA_STORAGE_SECRET_KEY}" -gt 256 ] ||
   [ "$MEDIA_STORAGE_ACCESS_KEY" = "$MEDIA_STORAGE_SECRET_KEY" ]; then
    echo 'STORAGE_CREDENTIAL_CONFIGURATION_INVALID' >&2
    exit 1
fi
printf '{"identities":[{"name":"media-local","credentials":[{"accessKey":"%s","secretKey":"%s"}],"actions":["Admin:libra-source","Admin:libra-hls"]}]}' \
    "$MEDIA_STORAGE_ACCESS_KEY" "$MEDIA_STORAGE_SECRET_KEY" > /run/media-secrets/s3.json
unset MEDIA_STORAGE_ACCESS_KEY MEDIA_STORAGE_SECRET_KEY AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY
exec weed mini -dir=/data -s3.config=/run/media-secrets/s3.json -webdav=false -admin.ui=false
