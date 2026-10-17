param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('frontend', 'realistic', 'supplement', 'closed-battle', 'public-data')]
    [string]$Scenario,
    [string]$MemberPointPassword = 'memberpoint-local-password',
    [string]$MarketPassword = 'market-local-password',
    [string]$BattlePassword = 'battle-local-password',
    [string]$InsightPassword = 'insight-local-password'
)

$seedRoot = $PSScriptRoot
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$targets = @{
    'member-point' = @{ Container = 'member-point-mysql'; Database = 'memberpoint'; User = 'memberpoint_user'; Password = $MemberPointPassword }
    'market' = @{ Container = 'market-mysql'; Database = 'market'; User = 'market_user'; Password = $MarketPassword }
    'battle' = @{ Container = 'battle-mysql'; Database = 'battle'; User = 'battle_user'; Password = $BattlePassword }
    'insight' = @{ Container = 'insight-mysql'; Database = 'insight'; User = 'insight_user'; Password = $InsightPassword }
}

$filesByScenario = @{
    'frontend' = @('member-point', 'market', 'battle', 'insight')
    'realistic' = @('battle', 'market', 'insight')
    'supplement' = @('insight', 'member-point', 'battle')
    'closed-battle' = @('battle')
    'public-data' = @('insight')
}

function Assert-MigratedDatabase {
    param(
        [string]$Service,
        [hashtable]$Target
    )

    $historyTableCount = & docker exec $Target.Container mysql "-u$($Target.User)" "-p$($Target.Password)" $Target.Database -N -s -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'flyway_schema_history'"
    if ($LASTEXITCODE -ne 0) {
        throw "Cannot connect to $Service database ($($Target.Container)/$($Target.Database)). Verify the MySQL container and credentials before running seed."
    }
    if ($historyTableCount.Trim() -ne '1') {
        throw "$Service database has not been migrated yet. Start $Service service and wait for Flyway migration before running seed."
    }

    $baselineCount = & docker exec $Target.Container mysql "-u$($Target.User)" "-p$($Target.Password)" $Target.Database -N -s -e "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1"
    if ($LASTEXITCODE -ne 0 -or $baselineCount.Trim() -lt 1) {
        throw "$Service database does not have a successful Flyway V1 migration. Start $Service service and wait for Flyway migration before running seed."
    }
}

foreach ($service in $filesByScenario[$Scenario]) {
    $path = Join-Path $seedRoot "$Scenario/$service.sql"
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Seed file not found: $path"
    }

    $target = $targets[$service]
    Assert-MigratedDatabase -Service $service -Target $target
    Write-Host "Applying $path to $($target.Container)/$($target.Database)"
    Get-Content -LiteralPath $path -Raw -Encoding utf8 |
        docker exec -i $target.Container mysql "-u$($target.User)" "-p$($target.Password)" $target.Database
    if ($LASTEXITCODE -ne 0) {
        throw "Seed failed: $path"
    }
}
