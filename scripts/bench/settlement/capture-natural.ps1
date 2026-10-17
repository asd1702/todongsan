param([Parameter(Mandatory=$true)][ValidateRange(26,40)][int]$Run, [int]$N = 10000, [switch]$TraceKernel)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$taskSession = Join-Path $taskRoot 'reports/bench/settlement/investigation-natural'
$taskCapture = Join-Path $taskSession "run$Run"
if (Test-Path -LiteralPath $taskCapture) { throw "Capture already exists: $taskCapture" }
New-Item -ItemType Directory -Path $taskCapture | Out-Null
$env:MEMBERPOINT_MYSQL_ROOT_PASSWORD = 'memberpoint-root-local-password'
$env:BENCH_POLL_TIMEOUT_SEC = '180'
$env:STALL_KERNEL_TRACE = if ($TraceKernel) { '1' } else { '0' }
$taskTraceStarted = $false
$taskJava = (Get-Command java).Source
$taskClassPath = (Join-Path $taskSession 'mysql-connector-j.jar') + ';' + $taskSession
$taskSampler = Start-Process -FilePath $taskJava -WindowStyle Hidden -PassThru -WorkingDirectory $taskRoot -ArgumentList @(
    '-cp', $taskClassPath,
    'StallSampler', 'capture', $taskCapture, '180', '50'
) -RedirectStandardOutput (Join-Path $taskCapture 'sampler.log') -RedirectStandardError (Join-Path $taskCapture 'sampler-error.log')
try {
    for ($taskAttempt=0; $taskAttempt -lt 15; $taskAttempt++) {
        if (Test-Path (Join-Path $taskCapture 'clock.tsv')) { break }
        if ($taskSampler.HasExited) { throw (Get-Content (Join-Path $taskCapture 'sampler-error.log') -Raw) }
        Start-Sleep -Milliseconds 500
    }
    if (-not (Test-Path (Join-Path $taskCapture 'clock.tsv'))) { throw 'Sampler did not start' }
    if ($TraceKernel) {
        $taskTraceExisting = docker ps -aq --filter 'name=^codex-mysql-stall-trace$'
        if ($taskTraceExisting) { throw 'Diagnostic container already exists; inspect it before continuing' }
        $taskMysqlPid = docker inspect --format '{{.State.Pid}}' member-point-mysql
        $taskMysqlPid = [int]$taskMysqlPid
        $taskVmProcesses = wsl.exe -d docker-desktop --exec sh -c 'grep -H NSpid /proc/[0-9]*/status 2>/dev/null'
        $taskVmMatch = @($taskVmProcesses | Select-String -Pattern "NSpid:\s+(\d+)\s+$taskMysqlPid\s+1\s*$")
        if ($taskVmMatch.Count -ne 1) { throw 'Could not uniquely map mysqld to the Linux VM PID namespace' }
        $taskVmPid = $taskVmMatch[0].Matches[0].Groups[1].Value
        $taskVmThreads = wsl.exe -d docker-desktop --exec sh -c "grep -H NSpid /proc/$taskVmPid/task/*/status"
        $taskVmThreads | Set-Content -Encoding UTF8 (Join-Path $taskCapture 'kernel-pids.tsv')
        $taskTracePids = ($taskVmThreads | ForEach-Object { if ($_ -match 'NSpid:\s+(\d+)') { $Matches[1] } }) -join ' '
        if (-not $taskTracePids) { throw 'No kernel thread PIDs found' }
        $taskTraceScript = Join-Path $PSScriptRoot 'trace-mysql.sh'
        docker run -d --name codex-mysql-stall-trace --user 0 --network none --read-only --tmpfs /trace --tmpfs /tmp:size=256m --pid=host --cap-add SYS_ADMIN --cap-add SYS_PTRACE --mount "type=bind,source=$taskTraceScript,target=/capture.sh,readonly" -e "MYSQL_PID=$taskMysqlPid" -e "MYSQL_TRACE_PIDS=$taskTracePids" --entrypoint sh grafana/k6:latest -c 'tr -d "\r" < /capture.sh > /tmp/capture.sh; sh /tmp/capture.sh; echo $? > /tmp/exit-code; touch /tmp/finished; sleep 600' | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not start kernel tracer' }
        $taskTraceStarted = $true
        $taskReady = $false
        for ($taskAttempt=0; $taskAttempt -lt 20; $taskAttempt++) {
            docker exec codex-mysql-stall-trace test -f /tmp/ready
            if ($LASTEXITCODE -eq 0) { $taskReady = $true; break }
            Start-Sleep -Milliseconds 500
        }
        if (-not $taskReady) { docker logs codex-mysql-stall-trace; throw 'Kernel tracer did not become ready' }
    }
    & 'C:/Program Files/Git/bin/bash.exe' scripts/bench/settlement/run.sh $Run $N 50 'natural-instrumented' 2>&1 |
        Tee-Object -FilePath (Join-Path $taskCapture 'run.log')
    $taskRunExit = $LASTEXITCODE
    $taskResult = Import-Csv (Join-Path $taskRoot 'reports/bench/settlement/results.csv') | Select-Object -Last 1
    if ($taskRunExit -ne 0 -or [int]$taskResult.run -ne $Run) { throw "Run $Run failed: exit=$taskRunExit" }
    $taskResult | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $taskCapture 'result.json')
    Copy-Item -LiteralPath (Join-Path $taskRoot 'reports/bench/settlement/mp.log') -Destination (Join-Path $taskCapture 'mp.log')
    Copy-Item -LiteralPath (Join-Path $taskRoot 'reports/bench/settlement/market.log') -Destination (Join-Path $taskCapture 'market.log')
    $taskMp = Get-NetTCPConnection -State Listen -LocalPort 8080 | Select-Object -First 1 -ExpandProperty OwningProcess
    & jcmd $taskMp JFR.dump name=natural-stall "filename=$taskCapture/mp.jfr"
    if ($taskResult.verify -ne 'PASS') { throw "VERIFY FAIL in run $Run; stop investigation immediately" }
} finally {
    try {
        New-Item -ItemType File -Path (Join-Path $taskCapture 'stop') -Force | Out-Null
        if (-not $taskSampler.WaitForExit(15000)) { Stop-Process -Id $taskSampler.Id; throw 'Sampler did not exit in 15 seconds' }
        Get-Content -LiteralPath (Join-Path $taskCapture 'sampler.log')
        if ($taskSampler.ExitCode -ne 0) { throw (Get-Content (Join-Path $taskCapture 'sampler-error.log') -Raw) }
    } finally {
        if ($taskTraceStarted) {
            $taskKernelDir = Join-Path $taskCapture 'kernel'
            New-Item -ItemType Directory -Path $taskKernelDir | Out-Null
            docker exec codex-mysql-stall-trace touch /tmp/stop
            for ($taskAttempt=0; $taskAttempt -lt 30; $taskAttempt++) {
                docker exec codex-mysql-stall-trace test -f /tmp/finished
                if ($LASTEXITCODE -eq 0) { break }
                Start-Sleep -Milliseconds 500
            }
            docker exec codex-mysql-stall-trace test -f /tmp/finished
            if ($LASTEXITCODE -ne 0) { throw 'Kernel tracer still running; diagnostic container retained for inspection' }
            foreach ($taskFile in 'trace.txt','trace-stats.txt','os.txt','fds.txt','threads.txt','stall-stacks.txt','exit-code','pid-filter.txt') {
                docker exec codex-mysql-stall-trace test -f "/tmp/$taskFile"
                if ($LASTEXITCODE -eq 0) {
                    docker exec codex-mysql-stall-trace cat "/tmp/$taskFile" | Set-Content -Encoding UTF8 (Join-Path $taskKernelDir $taskFile)
                    if ($LASTEXITCODE -ne 0) { throw 'Could not preserve kernel capture; diagnostic container retained' }
                }
            }
            docker logs codex-mysql-stall-trace > (Join-Path $taskKernelDir 'container.log')
            docker rm -f codex-mysql-stall-trace | Out-Null
            if (-not (Test-Path (Join-Path $taskKernelDir 'exit-code')) -or (Get-Content (Join-Path $taskKernelDir 'exit-code')).Trim() -ne '0') { throw 'Kernel tracer failed; inspect saved logs' }
        }
    }
}
& $taskJava -cp $taskSession StallAnalyze $taskCapture (Join-Path $taskRoot "reports/bench/settlement/runs/$($taskResult.run_dir)") > (Join-Path $taskCapture 'correlation-console.txt')
if ($LASTEXITCODE -ne 0) { throw 'Correlation analysis failed' }
Get-Content (Join-Path $taskCapture 'correlation.txt') | Select-Object -First 10
