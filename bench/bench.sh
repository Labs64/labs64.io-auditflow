#!/usr/bin/env bash
# CPU per event of the whole AuditFlow path at a fixed rate: the backend, transformer and sink images,
# RabbitMQ, Valkey and a Cerbos PDP enforcing the generated policies, driven by k6 through the
# trusted X-Auth-* headers (as behind the gateway). CPU comes from each container's cgroup, events
# from the backend's delivered counter; the queues are drained before and after the measured window.
#
#   bench/bench.sh up                       start the stack (needs auditflow-be/target/cerbos/policies:
#                                           mvn -pl auditflow-be -am package)
#   bench/bench.sh run RATE SECS LABEL [WARM_SECS]   e.g. run 100 60 baseline 60
#   bench/bench.sh backend                  recreate the backend only (to switch IMG_BE / BE_ENV)
#   bench/bench.sh sidecars                 recreate transformer + sink (IMG_TR / IMG_SK)
#   bench/bench.sh down
#
# Images default to the published ones; IMG_BE=auditflow-be:local (docker build -t ... auditflow-be)
# compares a branch. BE_ENV adds `docker create` options (e.g. "-e LABS64_AUTH_AUTHZ_DECISIONCACHETTL=5s"),
# JAVA_OPTS_EXTRA system properties, TENANTS_DIR another tenant directory. Other containers on the host
# move the numbers by ~10%: alternate the variants and compare several runs, not one.
#
# Profile: JFR_OPTS="-XX:FlightRecorderOptions:stackdepth=512 -XX:StartFlightRecording:delay=105s,
# duration=50s,filename=/tmp/rec.jfr,settings=profile,jdk.CPUTimeSample#enabled=true" bench/bench.sh
# backend, then `run`, `docker cp afb-backend:/tmp/rec.jfr .`, `python3 bench/jfr_stacks.py rec.jfr
# rec.stacks` and `python3 bench/stacks.py rec.stacks layers|bythread|within PAT|callers PAT`.
set -euo pipefail
B=$(cd "$(dirname "$0")" && pwd)
NET=afbench
IMG_BE=${IMG_BE:-labs64/auditflow:latest}
IMG_TR=${IMG_TR:-labs64/auditflow-transformer:latest}
IMG_SK=${IMG_SK:-labs64/auditflow-sink:latest}
POLICIES=$B/../auditflow-be/target/cerbos/policies
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
SVCS="backend transformer sink rabbitmq redis cerbos"

c() { echo "afb-$1"; }

up() {
  docker network create $NET >/dev/null 2>&1 || true
  docker run -d --name $(c rabbitmq) --network $NET --network-alias rabbitmq --cpus 2 --memory 512m \
    -e RABBITMQ_DEFAULT_USER=bench -e RABBITMQ_DEFAULT_PASS=bench rabbitmq:4.3-management-alpine >/dev/null
  docker run -d --name $(c redis) --network $NET --network-alias redis --cpus 1 --memory 128m valkey/valkey:9.1-alpine >/dev/null
  docker create --name $(c cerbos) --network $NET --network-alias cerbos --cpus 1 --memory 256m ghcr.io/cerbos/cerbos:0.56.0 server >/dev/null
  docker cp $POLICIES/. $(c cerbos):/policies
  docker start $(c cerbos) >/dev/null
  sidecars
  until docker exec $(c rabbitmq) rabbitmq-diagnostics -q check_port_connectivity >/dev/null 2>&1; do sleep 2; done
  backend
}

sidecars() {  # (re)create transformer and sink from IMG_TR / IMG_SK
  docker rm -f $(c transformer) $(c sink) >/dev/null 2>&1 || true
  docker run -d --name $(c transformer) --network $NET --network-alias transformer --cpus 0.5 --memory 128m $IMG_TR >/dev/null
  docker create --name $(c sink) --network $NET --network-alias sink --cpus 1 --memory 192m $IMG_SK >/dev/null
  docker cp $B/sinks_bootstrap/. $(c sink):/home/l64user/sinks_bootstrap
  docker start $(c sink) >/dev/null
  sleep 3
}

backend() {
  docker rm -f $(c backend) >/dev/null 2>&1 || true
  # shellcheck disable=SC2086
  docker create --name $(c backend) --network $NET --network-alias backend --cpus 2 --memory 1280m \
    -e JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 ${JFR_OPTS:-}" \
    -e RABBITMQ_USERNAME=bench -e RABBITMQ_PASSWORD=bench -e SPRING_RABBITMQ_HOST=rabbitmq \
    -e SPRING_DATA_REDIS_HOST=redis -e AUDITFLOW_IDEMPOTENCY_STORE=redis -e TENANTS_RATELIMIT_BACKEND=redis \
    -e TRANSFORMER_LOCAL_URL=http://transformer:8081 -e SINK_LOCAL_URL=http://sink:8082 \
    -e LABS64_AUTH_AUTHZ_PDPADDRESS=cerbos:3593 -e JAVA_OPTS="-Dtenants.source.local-dir.path=/home/l64user/tenants ${JAVA_OPTS_EXTRA:-}" \
    ${BE_ENV:-} $IMG_BE >/dev/null
  docker cp ${TENANTS_DIR:-$B/tenants}/. $(c backend):/home/l64user/tenants
  docker start $(c backend) >/dev/null
  for i in $(seq 1 90); do
    docker exec $(c backend) wget -qO- http://127.0.0.1:8080/actuator/health 2>/dev/null | grep -q UP && { echo "stack up"; return; }
    sleep 2
  done
  echo "backend did not become healthy"; docker logs --tail 40 $(c backend); exit 1
}

down() {
  for s in $SVCS k6; do docker rm -f $(c $s) >/dev/null 2>&1 || true; done
  docker network rm $NET >/dev/null 2>&1 || true
}

cpu_snapshot() {  # "name usec" per service; retried, since a lost output stream must not pass for 0
  local args="" out=""
  for s in $SVCS; do args="$args $s=$(docker inspect -f '{{.Id}}' $(c $s))"; done
  for i in 1 2 3 4 5; do
    out=$(docker run --rm -v /sys/fs/cgroup:/cg:ro python:3.14-alpine sh -c '
      for kv in '"$args"'; do n=${kv%%=*}; id=${kv#*=}; echo "$n $(awk "/^usage_usec/{print \$2}" /cg/docker/$id/cpu.stat)"; done' 2>/dev/null)
    [ "$(echo "$out" | awk 'NF==2' | wc -l)" -eq 6 ] && { echo "$out"; return; }
  done
  echo "cpu_snapshot failed" >&2; exit 1
}

metric() {  # sum of auditflow_delivery_outcomes_total for one outcome
  local out=""
  for i in 1 2 3 4 5; do
    out=$(docker exec $(c backend) wget -qO- http://127.0.0.1:8080/actuator/prometheus 2>/dev/null)
    [ -n "$out" ] && break
  done
  echo "$out" | awk -v o="outcome=\"$1\"" '/^auditflow_delivery_outcomes_total/ && index($0, o) {s+=$2} END {printf "%d\n", s}'
}

backlog() {  # messages waiting in the ingest and delivery queues (delay queues and DLQs excluded)
  local out=""
  for i in 1 2 3 4 5; do
    out=$(docker exec $(c rabbitmq) wget -qO- 'http://bench:bench@127.0.0.1:15672/api/queues/%2F?columns=name,messages' 2>/dev/null)
    [ -n "$out" ] && break
  done
  echo "$out" | python3 -c 'import json,sys; print(sum(q.get("messages",0) for q in json.load(sys.stdin) if q["name"] in ("labs64-audit-delivery","labs64-audit-topic.labs64.io-auditflow")))'
}

wait_idle() {  # until nothing waits in the queues (max $1 s)
  for i in $(seq 1 ${1:-60}); do [ "$(backlog)" = "0" ] && return 0; sleep 1; done
  echo "  (queues not empty after ${1:-60}s: $(backlog))"
}

delivered() { metric delivered; }

k6run() {  # rate secs [quiet]
  docker rm -f $(c k6) >/dev/null 2>&1 || true
  docker create --name $(c k6) --network $NET -e RATE=$1 -e DURATION=${2}s grafana/k6:2.3.0 run --quiet /home/k6/publish.js >/dev/null
  docker cp $B/publish.js $(c k6):/home/k6/publish.js
  docker start $(c k6) >/dev/null
  if [ "${3:-}" = quiet ]; then docker wait $(c k6) >/dev/null; else docker logs -f $(c k6) 2>&1 | grep -E 'http_req_duration|http_reqs|checks|http_req_failed' || true; docker wait $(c k6) >/dev/null; fi
}

run() {  # rate secs label [warm]
  local rate=$1 secs=$2 label=$3 warm=${4:-60}
  [ "$warm" -gt 0 ] && k6run $rate $warm quiet
  wait_idle 60
  local d0 d1 f0 f1 r0 r1; d0=$(delivered); f0=$(metric deferred); r0=$(metric retried); cpu_snapshot > $TMP/cpu0
  k6run $rate $secs
  wait_idle 60
  d1=$(delivered); f1=$(metric deferred); r1=$(metric retried); cpu_snapshot > $TMP/cpu1
  echo "== $label: rate=$rate/s window=${secs}s delivered=$((d1 - d0)) deferred=$((f1 - f0)) retried=$((r1 - r0))"
  join $TMP/cpu0 $TMP/cpu1 | awk -v n=$((d1 - d0)) -v s=$secs '
    {u=($3-$2)/1000; tot+=u; printf "  %-12s %8.0f ms CPU  %6.0f m avg  %6.3f ms/event\n", $1, u, u/s, u/n}
    END {printf "  %-12s %8.0f ms CPU  %6.0f m avg  %6.3f ms/event\n", "TOTAL", tot, tot/s, tot/n}'
}

case "${1:-}" in
  up) up ;;
  backend) backend ;;
  sidecars) sidecars ;;
  down) down ;;
  run) shift; run "$@" ;;
  *) echo "usage: $0 up|run RATE SECS LABEL [WARM]|down"; exit 2 ;;
esac
