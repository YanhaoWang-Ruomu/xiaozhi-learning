param(
    [switch]$LiveAi,
    [switch]$MultiTurnAi,
    [ValidateSet('SYNC','STREAM')][string]$AgentTransport = 'SYNC',
    [ValidateRange(1,3)][int]$AgentTrials = 1,
    [string]$AgentScenarios = 'all',
    [string]$MySqlHome = "$env:ProgramFiles\MySQL\MySQL Server 8.4",
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$MavenCommand = 'mvn.cmd',
    [string]$TestWorkRoot = (Join-Path $PSScriptRoot '..\..\xiaozhi-learning-backups')
)

# Starts only a disposable loopback instance. Never reads the business MySQL configuration/password.
$ErrorActionPreference = 'Stop'
if ($LiveAi -and $MultiTurnAi) { throw 'Choose only one live evaluation mode.' }
if ($AgentTransport -eq 'STREAM' -and !$MultiTurnAi) { throw 'STREAM requires -MultiTurnAi.' }
$serverExe = Join-Path $MySqlHome 'bin\mysqld.exe'
$adminExe = Join-Path $MySqlHome 'bin\mysqladmin.exe'
if (!(Test-Path $serverExe) -or !(Test-Path $adminExe)) { throw 'MySQL 8.4 binaries not found; pass -MySqlHome.' }
if (!$JavaHome -or !(Test-Path (Join-Path $JavaHome 'bin\java.exe'))) { throw 'Pass a valid Java 17 -JavaHome.' }
$mavenExe = (Get-Command $MavenCommand -ErrorAction Stop).Source
if (Get-NetTCPConnection -LocalPort 13307 -State Listen -ErrorAction SilentlyContinue) {
    throw 'Port 13307 is occupied. This script will not stop or reuse an unknown server.'
}
$mongoProbe = New-Object Net.Sockets.TcpClient
try { $mongoProbe.Connect('127.0.0.1', 27017) }
catch { throw 'Start local MongoDB on 127.0.0.1:27017 before running this test.' }
finally { $mongoProbe.Dispose() }

$TestWorkRoot = [IO.Path]::GetFullPath($TestWorkRoot)
if ($TestWorkRoot -match '[^\x00-\x7F]') { throw 'Pass -TestWorkRoot with an ASCII-only path for MySQL on Windows.' }
$work = Join-Path $TestWorkRoot ('xiaozhi-mysql-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $work | Out-Null
$data = Join-Path $work 'data'
$server = $null
$result = 1
$oldJava = $env:JAVA_HOME
$oldUser = $env:XIAOZHI_TEST_MYSQL_USER
$oldPassword = $env:XIAOZHI_TEST_MYSQL_PASSWORD
try {
    $init = Start-Process -FilePath $serverExe -ArgumentList @('--no-defaults', '--initialize-insecure',
        ('"--basedir=' + $MySqlHome + '"'), ('"--datadir=' + $data + '"')) -PassThru -Wait -WindowStyle Hidden `
        -RedirectStandardOutput "$work\initialize.stdout.log" -RedirectStandardError "$work\initialize.stderr.log"
    if ($init.ExitCode -ne 0) { throw "Temporary MySQL initialization failed; see $work" }
    $server = Start-Process -FilePath $serverExe -ArgumentList @('--no-defaults', '--console', '--port=13307',
        '--bind-address=127.0.0.1', '--mysqlx=0', '--innodb-buffer-pool-size=64M', '--max-connections=30',
        ('"--datadir=' + $data + '"'), ('"--log-error=' + $work + '\mysql.log"')) -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput "$work\server.stdout.log" -RedirectStandardError "$work\server.stderr.log"
    $ready = $false
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($server.HasExited) { throw "Temporary MySQL exited; see $work" }
        $probe = New-Object Net.Sockets.TcpClient
        try { $probe.Connect('127.0.0.1', 13307); $ready = $true; break }
        catch { Start-Sleep -Milliseconds 200 }
        finally { $probe.Dispose() }
    }
    if (!$ready) { throw 'Temporary MySQL startup timed out.' }
    $env:JAVA_HOME = $JavaHome
    $env:XIAOZHI_TEST_MYSQL_USER = 'root'
    $env:XIAOZHI_TEST_MYSQL_PASSWORD = ''
    Push-Location (Join-Path $PSScriptRoot '..\backend')
    try {
        # PowerShell 5 treats native stderr warnings as ErrorRecords; trust the Maven exit code.
        $ErrorActionPreference = 'Continue'
        & $mavenExe '-Dxiaozhi.auth.integration=true' '-Dxiaozhi.mysql.integration=true' verify *> "$work\maven.log"
        $result = $LASTEXITCODE
        if ($result -eq 0 -and ($LiveAi -or $MultiTurnAi)) {
            $liveNames = if ($MultiTurnAi) { @('DASHSCOPE_API_KEY') } else { @('DASHSCOPE_API_KEY','PINECONE_API_KEY','PINECONE_INDEX_HOST') }
            foreach ($name in $liveNames) {
                $value = [Environment]::GetEnvironmentVariable($name,'User')
                if (!$value) { throw "Missing live evaluation variable: $name" }
                [Environment]::SetEnvironmentVariable($name,$value,'Process')
            }
            & $mavenExe '-q' 'dependency:build-classpath' '-Dmdep.outputFile=target/live-classpath.txt' '-Dmdep.includeScope=test'
            if ($LASTEXITCODE -ne 0) { throw 'Could not build evaluation classpath' }
            $cp = 'target/classes;target/test-classes;' + (Get-Content -Raw -Encoding UTF8 target/live-classpath.txt).Trim()
            $prefix = if ($MultiTurnAi) { 'agent-multiturn-' + $AgentTransport.ToLowerInvariant() + '-' } else { 'live-agent-' }
            $out = Join-Path $PSScriptRoot ('../evals/results/' + $prefix + (Get-Date -Format 'yyyyMMdd-HHmmss'))
            New-Item -ItemType Directory (Split-Path $out) -Force | Out-Null
            if ($MultiTurnAi) {
                & (Join-Path $JavaHome 'bin/java.exe') '-cp' $cp 'com.ruomu.xiaozhi.service.AgentMultiTurnEval' $out $AgentTrials $AgentScenarios $AgentTransport *> "$work/live-ai.log"
            } else {
                & (Join-Path $JavaHome 'bin/java.exe') '-cp' $cp 'com.ruomu.xiaozhi.service.AppointmentMySqlIntegrationTest' $out *> "$work/live-ai.log"
            }
            $result = $LASTEXITCODE
            Get-Content "$work/live-ai.log" -Tail 15
        }
        $ErrorActionPreference = 'Stop'
        Get-Content "$work\maven.log" -Tail 30
    } finally { Pop-Location }
} finally {
    $env:JAVA_HOME = $oldJava
    $env:XIAOZHI_TEST_MYSQL_USER = $oldUser
    $env:XIAOZHI_TEST_MYSQL_PASSWORD = $oldPassword
    if ($server -and !$server.HasExited) {
        try {
            $stop = Start-Process -FilePath $adminExe -ArgumentList @('--no-defaults', '--protocol=tcp',
                '--host=127.0.0.1', '--port=13307', '--user=root', '--skip-password', 'shutdown') `
                -PassThru -WindowStyle Hidden -RedirectStandardOutput "$work\shutdown.stdout.log" `
                -RedirectStandardError "$work\shutdown.stderr.log"
            if (!$stop.WaitForExit(10000)) { Stop-Process -Id $stop.Id }
            if (!$server.WaitForExit(10000)) { Stop-Process -Id $server.Id; $server.WaitForExit() }
        } catch {
            if (!$server.HasExited) { Stop-Process -Id $server.Id; $server.WaitForExit() }
        }
    }
    # The data path was generated by this invocation. Keep logs, remove only its disposable data.
    if (Test-Path $data) { Remove-Item -LiteralPath $data -Recurse -Force }
    Write-Output "TEST_LOGS=$work"
}
Write-Output "MYSQL_TEST_RUNNER_EXIT=$result"
exit $result