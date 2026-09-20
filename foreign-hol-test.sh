#!/usr/bin/env bash
set -u

proxy="127.0.0.1:11081"
result_dir="$(mktemp -d /tmp/foreign-hol.XXXXXX)"

run_request() {
  local name="$1"
  local url="$2"
  local start end result exit_code http_code
  start="$(date +%s%3N)"
  result="$(env NO_PROXY= no_proxy= curl --socks5-hostname "$proxy" --connect-timeout 8 --max-time 30 --silent --show-error -o "$result_dir/$name.body" -w '%{http_code}' "$url" 2>"$result_dir/$name.err")"
  exit_code=$?
  end="$(date +%s%3N)"
  http_code="$result"
  if [ "$exit_code" -ne 0 ]; then
    http_code="curl_exit_$exit_code"
  fi
  printf '%s code=%s elapsed_ms=%s url=%s\n' "$name" "$http_code" "$((end - start))" "$url"
}

run_request slow "https://httpbin.org/delay/8" &
slow_pid=$!
sleep 0.4
run_request google "https://www.google.com/generate_204" &
google_pid=$!
run_request youtube "https://www.youtube.com/generate_204" &
youtube_pid=$!
run_request cloudflare "https://www.cloudflare.com/cdn-cgi/trace" &
cloudflare_pid=$!

wait "$slow_pid" "$google_pid" "$youtube_pid" "$cloudflare_pid"
