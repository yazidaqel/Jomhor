#!/usr/bin/env bash
# Local two-instance smoke (plan lot-5 §5 BE-44, §5.5): Postgres + api-1 (8081) + api-2 (8082) in their own compose
# project (jomhor-smoke: own volume, DB on 5436), readiness of both, each k6 scenario in turn against both, then
# check.sql and the order check (every order k6 got a 201 for exists, from the ids k6 writes to
# build/loadtest/orders-*.log). Each k6 run has a hard limit (DURATION_S + 240 s: the buy setup's wait for the draw,
# the graceful stop, a margin); one that hits it fails the smoke. Everything is removed at the end, the trap included;
# the dev stack (project backend, DB on 5435) is never touched.
#   bash backend/loadtest/smoke.sh
# Size: VUS (5), DURATION_S (30) per scenario. Seed: LOADTEST_FANS (7000), LOADTEST_SALE_IN (150s: the pre-queue and
# poll runs come before the draw), LOADTEST_RATE (6000/min: smoke buyers get their turn inside 30 s). Memory caps:
# API_MEM_LIMIT (768m each), PG_MEM_LIMIT (1g). k6 runs from the host if installed, else from grafana/k6 on the
# compose network.
set -uo pipefail
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1 # Git Bash: keep /scripts and /seed as they are
ROOT=$(pwd -W 2>/dev/null || pwd)

export DB_PORT=${SMOKE_DB_PORT:-5436} PG_MEM_LIMIT=${PG_MEM_LIMIT:-1g} API_MEM_LIMIT=${API_MEM_LIMIT:-768m}
export LOADTEST_FANS=${LOADTEST_FANS:-7000} LOADTEST_SALE_IN=${LOADTEST_SALE_IN:-150s} LOADTEST_RATE=${LOADTEST_RATE:-6000}
export PAYMENTS_FAKE_SECRET=${PAYMENTS_FAKE_SECRET:-smoke-only-payments-fake-secret-0001}
VUS=${VUS:-5}
DURATION_S=${DURATION_S:-30}
LIMIT=$((DURATION_S + 240))
K6_IMAGE=grafana/k6:2.3.0
K6_NAME=jomhor-smoke-k6
OUT=build/loadtest # the seed file (api-1 writes it) and k6's order ids
compose() { docker compose -p jomhor-smoke --profile smoke "$@"; }
sql() { compose exec -T postgres psql -U "${DB_USER:-jomhor}" -d "${DB_NAME:-jomhor}" -v ON_ERROR_STOP=1 -qAt "$@"; }

cleanup() {
  echo "== teardown"
  docker rm -f "$K6_NAME" >/dev/null 2>&1
  compose down -v --remove-orphans
  docker image rm -f jomhor-api:smoke >/dev/null 2>&1
  rm -f "$OUT"/seed.json "$OUT"/orders-*.log
}

echo "== boot jar"
./gradlew -q bootJar || exit 1
./gradlew -q --stop # no Gradle daemon next to the containers

rm -f "$OUT"/seed.json "$OUT"/orders-*.log
mkdir -p "$OUT"
trap cleanup EXIT
trap "exit 1" INT TERM # a stopped smoke still tears down
echo "== up"
compose up -d --build postgres api-1 api-2 || exit 1

echo "== readiness"
for port in 8081 8082; do
  ready=
  for _ in $(seq 1 120); do
    ready=$(curl -fsS "http://localhost:$port/v1/actuator/health/readiness" 2>/dev/null) && break
    sleep 2
  done
  echo "$port: ${ready:-not ready}"
  [[ $ready == *'"UP"'* ]] || { compose logs --tail 50 "api-$((port - 8080))"; exit 1; }
done

# k6run NAME SCRIPT ARGS...: one k6 run under the hard limit; console.log (the order ids) to $OUT/orders-NAME.log.
k6run() {
  local name=$1 script=$2 code
  shift 2
  echo "== k6 $script $name"
  if command -v k6 >/dev/null; then
    BASE_URLS=http://localhost:8081/v1,http://localhost:8082/v1 SEED="$ROOT/$OUT/seed.json"       timeout -k 10 "$LIMIT" k6 run --quiet --console-output "$ROOT/$OUT/orders-$name.log"       "$ROOT/loadtest/k6/$script" -e DURATION_S="$DURATION_S" "$@"
  else
    timeout -k 10 "$LIMIT" docker run --rm --name "$K6_NAME" --network jomhor-smoke_default --user "$(id -u):$(id -g)"       -v "$ROOT/loadtest/k6:/scripts:ro" -v "$ROOT/$OUT:/out"       -e BASE_URLS=http://api-1:8080/v1,http://api-2:8080/v1 -e SEED=/out/seed.json -e PAYMENTS_FAKE_SECRET       "$K6_IMAGE" run --quiet --console-output "/out/orders-$name.log" "/scripts/$script" -e DURATION_S="$DURATION_S" "$@"
  fi
  code=$?
  if ((code == 124 || code == 137)); then
    echo "k6 $script $name: no end after ${LIMIT} s, stopped"
    docker rm -f "$K6_NAME" >/dev/null 2>&1
  fi
  return $code
}

failed=0
for scenario in prequeue poll buy cold-visitor; do
  k6run "$scenario" onsale.js --vus "$VUS" --duration "${DURATION_S}s" -e SCENARIO="$scenario" || failed=1
done
k6run hot-row hot-row.js --vus "$VUS" --duration "${DURATION_S}s" || failed=1

echo "== docker stats"
docker stats --no-stream $(compose ps -q)

echo "== check.sql"
breaches=$(sql -F ' | ' < loadtest/check.sql) || failed=1
if [[ -n $breaches ]]; then
  echo "$breaches"
  failed=1
else
  echo "no breach"
fi

echo "== orders"
ids=$(cat "$OUT"/orders-*.log 2>/dev/null | grep -oE 'ORDER [0-9a-f-]{36}' | cut -d' ' -f2)
seen=$(grep -c . <<<"$ids")
unique=$(sort -u <<<"$ids" | grep -c .)
missing=$({
  echo 'create temp table seen (id uuid primary key); copy seen from stdin;'
  sort -u <<<"$ids" | grep .
  echo '\.'
  echo 'select count(*) from seen s where not exists (select 1 from orders o where o.id = s.id);'
} | sql | tail -n 1) || failed=1
echo "201s seen by k6: $seen, distinct: $unique, missing from the database: ${missing:-?}"
[[ $seen -gt 0 && $seen == "$unique" && $missing == 0 ]] || failed=1

echo "== smoke: $([[ $failed == 0 ]] && echo PASSED || echo FAILED)"
exit $failed
