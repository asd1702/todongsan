<#
Runs identical, bounded request batches against the profile-only Insight
resilience probe. It intentionally has no dependency on k6, wrk, or a database.
Requires PowerShell 7+ for parallel execution.
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8083",
    [long]$MarketId = 1,
    [ValidateRange(1, 1000)]
    [int]$RequestCount = 20,
    [ValidateRange(1, 100)]
    [int]$Concurrency = 8,
    [string]$OutputDirectory = "scripts/resilience/results"
)

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw "PowerShell 7 or later is required because this lab uses ForEach-Object -Parallel."
}

$uri = "$BaseUrl/internal/lab/resilience/market/$MarketId"
$outputPath = Join-Path $OutputDirectory ("insight-market-{0:yyyyMMdd-HHmmss}" -f (Get-Date))
New-Item -ItemType Directory -Force -Path $outputPath | Out-Null

$requests = 1..$RequestCount
$results = $requests | ForEach-Object -Parallel {
    $requestNumber = $_
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $response = Invoke-WebRequest -Uri $using:uri -Method Get -TimeoutSec 15 -SkipHttpErrorCheck
        $watch.Stop()
        [PSCustomObject]@{
            Request = $requestNumber
            StatusCode = [int]$response.StatusCode
            Success = $response.StatusCode -ge 200 -and $response.StatusCode -lt 300
            LatencyMs = $watch.Elapsed.TotalMilliseconds
            Error = $null
        }
    } catch {
        $watch.Stop()
        [PSCustomObject]@{
            Request = $requestNumber
            StatusCode = $null
            Success = $false
            LatencyMs = $watch.Elapsed.TotalMilliseconds
            Error = $_.Exception.Message
        }
    }
} -ThrottleLimit $Concurrency

$results | Sort-Object Request | Export-Csv -NoTypeInformation -Encoding utf8 -Path (Join-Path $outputPath "requests.csv")
$latencies = @($results | ForEach-Object { [double]$_.LatencyMs } | Sort-Object)
function Get-Percentile([double[]]$Values, [double]$Percentile) {
    if ($Values.Count -eq 0) { return 0 }
    $index = [Math]::Min($Values.Count - 1, [Math]::Ceiling($Values.Count * $Percentile) - 1)
    return [Math]::Round($Values[$index], 2)
}

$summary = [PSCustomObject]@{
    Uri = $uri
    RequestCount = $RequestCount
    Concurrency = $Concurrency
    Success = @($results | Where-Object Success).Count
    Failure = @($results | Where-Object { -not $_.Success }).Count
    AverageLatencyMs = [Math]::Round((($latencies | Measure-Object -Average).Average), 2)
    P95LatencyMs = Get-Percentile $latencies 0.95
    P99LatencyMs = Get-Percentile $latencies 0.99
    Output = $outputPath
}
$summary | ConvertTo-Json | Set-Content -Encoding utf8 -Path (Join-Path $outputPath "summary.json")
$summary | Format-List
