#!/bin/sh
# Run inside the disposable, network-disabled diagnostic container with host PID visibility.
# Writes only to /tmp and its own tracefs instance. MYSQL_PID is the Docker-host mysqld PID.
set -eu
case "${MYSQL_PID:-}" in ''|*[!0-9]*) echo 'MYSQL_PID must be numeric' >&2; exit 1;; esac
epoch() { chroot "/proc/$MYSQL_PID/root" /bin/date +%s%N; }
if [ "${1:-}" = snapshot ]; then
    {
        printf 'SNAPSHOT epoch_ns=%s\n' "$(epoch)"
        for task in /proc/"$MYSQL_PID"/task/*; do
            printf '\nTHREAD %s\n' "${task##*/}"
            cat "$task/comm" "$task/syscall" "$task/stack" "$task/schedstat" "$task/wchan" 2>/dev/null || true
        done
        printf '\nEND epoch_ns=%s\n' "$(epoch)"
    } >> /tmp/stall-stacks.txt
    exit 0
fi

test "$(cat /proc/$MYSQL_PID/comm)" = mysqld
mount -t tracefs tracefs /trace
trace=/trace/instances/codex-mysql-stall
mkdir "$trace"
reader=''
cleanup() {
    echo 0 > "$trace/tracing_on"
    sleep 0.1
    if [ -n "$reader" ]; then kill "$reader" 2>/dev/null || true; wait "$reader" 2>/dev/null || true; fi
    cat "$trace/trace" >> /tmp/trace.txt
    for stats in "$trace"/per_cpu/cpu*/stats; do printf '%s\n' "$stats"; cat "$stats"; done > /tmp/trace-stats.txt
    echo 0 > "$trace/events/enable"
    rmdir "$trace"
    umount /trace
}
trap cleanup EXIT
echo 0 > "$trace/tracing_on"
echo mono > "$trace/trace_clock"
echo 1024 > "$trace/buffer_size_kb"
test -n "${MYSQL_TRACE_PIDS:-}"
printf '%s\n' "$MYSQL_TRACE_PIDS" > "$trace/set_event_pid"
cat "$trace/set_event_pid" > /tmp/pid-filter.txt
echo 1 > "$trace/options/event-fork"
for syscall in fsync fdatasync write pwrite64 fallocate ftruncate io_submit io_getevents; do
    for direction in enter exit; do
        event="$trace/events/syscalls/sys_${direction}_${syscall}/enable"
        if [ -f "$event" ]; then echo 1 > "$event"; fi
    done
done
cat "$trace/trace_pipe" > /tmp/trace.txt &
reader=$!
echo 1 > "$trace/tracing_on"
touch /tmp/ready
iteration=0
while [ ! -f /tmp/stop ] && [ "$iteration" -lt 1800 ]; do
    stamp=$(epoch)
    printf 'clock epoch_ns=%s\n' "$stamp" > "$trace/trace_marker"
    {
        printf 'SAMPLE epoch_ns=%s\n' "$stamp"
        cat /proc/"$MYSQL_PID"/io /proc/pressure/io /proc/pressure/cpu /proc/pressure/memory
    } >> /tmp/os.txt
    if [ $((iteration % 10)) -eq 0 ]; then
        {
            printf 'SAMPLE epoch_ns=%s\n' "$stamp"
            ls -l /proc/"$MYSQL_PID"/fd
        } >> /tmp/fds.txt
        {
            printf 'SAMPLE epoch_ns=%s\n' "$stamp"
            awk '/^Name:|^NSpid:/{print FILENAME " " $0}' /proc/"$MYSQL_PID"/task/*/status
        } >> /tmp/threads.txt
    fi
    iteration=$((iteration + 1))
    sleep 0.1
done
