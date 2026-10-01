#!/usr/bin/env bash
# redo 용량 비교 실험 1회 (docs/architecture/ASYNC_POINT_FLOW.md §17.8)
#
#   ./scripts/bench/settlement/redo-run.sh <run> <label> [N=10000]
#
# run.sh 1회에 MP MySQL의 redo 관련 지표를 덧붙여 redo-results.csv에 한 줄을 남긴다.
#   log_files_mutex / log_writer_mutex : run 동안 최장 대기(ms)와 50ms 이상 대기가 있었는지 (instrument 2개만 켠다)
#   redo_switches                       : redo 파일 교체 횟수 (innodb_redo_log_files의 최대 FILE_ID 증가분)
#   spend_gap_ms                        : 정산 시작~MP 완료+1초 사이 spend 완료 간 최장 공백 (전체 정지면 400ms 이상)
set -euo pipefail
export MSYS_NO_PATHCONV=1

run="${1:?run}"; label="${2:?label}"; n="${3:-10000}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$DIR/../../../reports/bench/settlement"
CSV="$OUT/redo-results.csv"
pq() { docker exec -i -e MYSQL_PWD="${MEMBERPOINT_MYSQL_ROOT_PASSWORD:-memberpoint-root-local-password}" member-point-mysql mysql -uroot -N "$@" 2>/dev/null; }
MUTEXES="'wait/synch/mutex/innodb/log_files_mutex','wait/synch/mutex/innodb/log_writer_mutex'"

pq -e "UPDATE performance_schema.setup_instruments SET ENABLED='YES', TIMED='YES' WHERE NAME IN ($MUTEXES);
       TRUNCATE performance_schema.events_waits_summary_global_by_event_name;"
cap_mb=$(pq -e "SELECT @@innodb_redo_log_capacity DIV 1048576")
file_id0=$(pq -e "SELECT MAX(FILE_ID) FROM performance_schema.innodb_redo_log_files")

BENCH_POLL_TIMEOUT_SEC=900 "$DIR/run.sh" "$run" "$n" 50 "$label" > /dev/null
row=$(tail -1 "$OUT/results.csv")

file_id1=$(pq -e "SELECT MAX(FILE_ID) FROM performance_schema.innodb_redo_log_files")
read -r files_max files_cnt writer_max writer_cnt <<< "$(pq -e "
  SELECT ROUND(MAX(CASE WHEN EVENT_NAME LIKE '%log_files_mutex' THEN MAX_TIMER_WAIT END)/1e9, 1),
         MAX(CASE WHEN EVENT_NAME LIKE '%log_files_mutex' THEN COUNT_STAR END),
         ROUND(MAX(CASE WHEN EVENT_NAME LIKE '%log_writer_mutex' THEN MAX_TIMER_WAIT END)/1e9, 1),
         MAX(CASE WHEN EVENT_NAME LIKE '%log_writer_mutex' THEN COUNT_STAR END)
  FROM performance_schema.events_waits_summary_global_by_event_name WHERE EVENT_NAME IN ($MUTEXES)" | tr '\t' ' ')"

run_dir="$OUT/runs/$(echo "$row" | awk -F, '{print $NF}')"
mp_done=$(tr -d '\r\n' < "$run_dir/mp_done")
gap=$(grep '"metric":"http_req_duration","type":"Point"' "$run_dir/k6.json" \
  | grep -oE '"time":"[^"]+","value":[0-9.]+,"tags":\{[^}]*"name":"(spend|settle)' \
  | sed -E 's/"time":"[0-9-]+T([0-9:.]+)Z","value":([0-9.]+).*"name":"([a-z]+)/\1 \2 \3/' \
  | awk -v mp="$mp_done" 'function ms(t){split(t,a,":"); return (a[1]*3600+a[2]*60+a[3])*1000}
      $3=="settle" {s=ms($1)} $3=="spend" {e[++n]=ms($1)}
      END {mpd=mp%86400000; m=0; prev=0
           asort(e); for(i=1;i<=n;i++){ if(e[i]<s || e[i]>mpd+1000) continue; if(prev && e[i]-prev>m) m=e[i]-prev; prev=e[i] } printf "%.0f", m}')

[ -f "$CSV" ] || echo "run,label,redo_capacity_mb,mp_done_ms,market_done_ms,spend_p95,spend_max,spend_gap_ms,redo_switches,log_files_mutex_max_ms,log_files_mutex_count,log_writer_mutex_max_ms,log_writer_mutex_count,verify" > "$CSV"
echo "$row" | awk -F, -v cap="$cap_mb" -v gap="$gap" -v sw="$((file_id1 - file_id0))" -v fm="$files_max" -v fc="$files_cnt" -v wm="$writer_max" -v wc="$writer_cnt" \
  '{print $5","$2","cap","$11","$12","$19","$21","gap","sw","fm","fc","wm","wc","$32}' | tee -a "$CSV"
