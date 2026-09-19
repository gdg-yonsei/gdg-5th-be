# feed-lab · Windows PowerShell 용 (Makefile 과 같은 명령)
#   .\lab.ps1 up | seed [-Scale 0.5] [-FeedDays 3] [-Force] | reset | warm | check | explain | stats | logs | down | nuke
# 처음 한 번:  Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
param(
    [Parameter(Position = 0)][string]$Cmd = "help",
    [double]$Scale = 1,        # 1 = 발표용(유저 10만). 노트북은 0.5 권장 (최소 0.4)
    [int]$FeedDays = 3,
    [int]$DbCpus = 2,
    [int]$SeedCpus = 6,
    [switch]$Force
)
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8   # 컨테이너가 내보내는 한글이 깨지지 않게
$env:DB_CPUS = "$DbCpus"
$PG = "feedlab-postgres"

function Psql { param([string[]]$Rest) docker exec $PG psql -U postgres -d feedlab @Rest }
function Q([string]$Sql) {   # 값 하나 조회. 컨테이너가 아직 없어도 오류로 멈추지 않는다 (PS 5.1 은 stderr 리다이렉트를 오류로 본다)
    $prev = $ErrorActionPreference; $ErrorActionPreference = "Continue"
    try { $r = docker exec $PG psql -U postgres -d feedlab -Atc $Sql 2>$null; return "$r".Trim() } finally { $ErrorActionPreference = $prev }
}
function Seeded { (Q "SELECT count(*) FROM seed_meta") -eq "1" }
function Py { if (Get-Command py -ErrorAction SilentlyContinue) { py -3 @args } else { python @args } }

switch ($Cmd) {
    "up" {
        docker compose up -d --build
        if (Seeded) { Write-Host "캐시 올리는 중 (warm)..."; Psql @("-q", "-f", "/seed/warm.sql") | Out-Null }
        Write-Host "→ http://localhost:8080   (처음이면 .\lab.ps1 seed, 발표 전에는 .\lab.ps1 check)"
    }
    "seed" {
        while ((Q "SELECT 1 FROM information_schema.tables WHERE table_name='fanout_jobs'") -ne "1") {
            Write-Host "앱 마이그레이션 대기..."; Start-Sleep 2
        }
        if (-not $Force -and (Seeded)) { Write-Host "이미 시드되어 있습니다. 다시 하려면: .\lab.ps1 seed -Force"; exit 1 }
        $users = [int]($Scale * 100000); $posts = [int]($Scale * 3000000)
        docker update --cpus $SeedCpus $PG | Out-Null
        Write-Host "users=$users posts=$posts feed_days=$FeedDays"
        $sw = [Diagnostics.Stopwatch]::StartNew()
        try { Psql @("-v", "users=$users", "-v", "posts=$posts", "-v", "feed_days=$FeedDays", "-f", "/seed/seed.sql") }
        finally { docker update --cpus $DbCpus $PG | Out-Null }
        if ($LASTEXITCODE -ne 0) { Write-Host "시드 실패. 다시 하려면: .\lab.ps1 nuke; .\lab.ps1 up; .\lab.ps1 seed"; exit 1 }
        Write-Host ("시드 {0:n0}초" -f $sw.Elapsed.TotalSeconds)
        Psql @("-q", "-f", "/seed/warm.sql")
        docker compose restart app loadgen
    }
    "reset"   { Invoke-RestMethod -Method Post http://localhost:8080/admin/reset | ConvertTo-Json -Compress }
    "warm"    { Psql @("-f", "/seed/warm.sql") }
    "explain" { Psql @("-f", "/seed/explain.sql") }
    "check"   { Py scripts/check.py }
    "stats"   { docker stats --no-stream $PG feedlab-app feedlab-loadgen }
    "logs"    { docker compose logs -f --tail=50 app loadgen }
    "down"    { docker compose down }
    "nuke"    { docker compose down -v }
    default   { Get-Content $PSCommandPath -TotalCount 3 }
}
