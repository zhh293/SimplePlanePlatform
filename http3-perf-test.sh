#!/usr/bin/env bash
set -u
target_url="http://h3-perf-target.test:18080/"

# Keep the target off the local-address DIRECT rules and make curl use SOCKS5
# even when the host environment defines NO_PROXY/no_proxy.
seq 1 40 | xargs -P 20 -I{} env NO_PROXY= no_proxy= \
  curl --socks5-hostname 127.0.0.1:11081 --max-time 15 \
  --silent --show-error -o /dev/null -w "%{http_code}\n" "$target_url" \
  | sort | uniq -c
