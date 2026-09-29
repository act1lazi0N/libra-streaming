#!/bin/sh
set -eu
fail() { echo "MEDIA_M06_HTTP_FAIL: $1" >&2; exit 1; }
request() { curl --silent --output /dev/null --max-time 8 --write-out '%{http_code}' "$@"; }
signed() { request --aws-sigv4 aws:amz:us-east-1:s3 --user "$PROBE_ACCESS:$PROBE_SECRET" "$@"; }
if [ "${1:-}" = down ]; then
    [ "$(request http://media:8082/actuator/health/readiness)" = 503 ] || fail readiness-down
    [ "$(request http://media:8082/actuator/health/liveness)" = 200 ] || fail liveness
    echo MEDIA_M06_OUTAGE_PASS
    exit 0
fi
[ "$(request http://media:8082/actuator/health/readiness)" = 200 ] || fail readiness-up
# Seed existing objects so anonymous GET denial is meaningful.
for bucket in libra-source libra-hls; do
    path="$bucket/$( [ "$bucket" = libra-source ] && echo sources || echo hls )/m06-private"
    url="http://s3-gateway:8333/$path"
    [ "$(signed -X PUT --data-binary private-fixture "$url")" = 200 ] || fail signed-put
    [ "$(signed "$url")" = 200 ] || fail signed-get
    [ "$(signed "http://s3-gateway:8333/$bucket?list-type=2")" = 200 ] || fail signed-list
    [ "$(request "$url")" = 403 ] || fail anonymous-get
    [ "$(request "http://s3-gateway:8333/$bucket?list-type=2")" = 403 ] || fail anonymous-list
    [ "$(request -X PUT --data-binary overwritten "$url")" = 403 ] || fail anonymous-put
    body=$(curl --silent --fail --max-time 8 --aws-sigv4 aws:amz:us-east-1:s3 --user "$PROBE_ACCESS:$PROBE_SECRET" "$url")
    [ "$body" = private-fixture ] || fail anonymous-put-side-effect
    [ "$(signed -X DELETE "$url")" = 204 ] || fail signed-delete
done
for path in filer master volume seaweedfs s3 buckets libra-source libra-hls dir/assign cluster/status status; do
    [ "$(request "http://nginx/$path?X-Amz-Signature=m06-query-canary" -H 'Authorization: Bearer m06-header-canary')" = 404 ] || fail public-storage-route
    [ "$(request "http://s3-gateway:8333/$path")" = 404 ] || case "$path" in libra-source|libra-hls) :;; *) fail gateway-route;; esac
done
[ "$(request http://nginx/api/media/internal/v1/uploads)" = 404 ] || fail private-media-route
for port in 8333 8888 9333 9340 8080 18080 18888 19333; do
    if curl --silent --output /dev/null --connect-timeout 1 --max-time 1 "http://$PRIVATE_STORAGE_IP:$port/"; then
        fail private-network-bypass
    fi
done
# Exercise proxy errors without allowing request credentials into error logs.
request 'http://s3-gateway:8333/libra-source/sources/private?X-Amz-Signature=m06-query-canary' \
    -H 'Authorization: Bearer m06-header-canary' >/dev/null
echo MEDIA_M06_HTTP_PASS
