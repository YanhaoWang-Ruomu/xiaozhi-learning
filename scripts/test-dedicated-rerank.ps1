param(
    [switch]$Live,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$MavenCommand = 'mvn.cmd'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if (!$JavaHome -or !(Test-Path (Join-Path $JavaHome 'bin/java.exe'))) { throw 'Pass -JavaHome with Java 17.' }
$oldJava = $env:JAVA_HOME
$names = @('DASHSCOPE_API_KEY','PINECONE_API_KEY','PINECONE_INDEX_HOST')
$saved = @{}
foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
Push-Location $root
try {
    $env:JAVA_HOME = $JavaHome
    & $MavenCommand -B -ntp -f backend/pom.xml '-Dtest=DedicatedRerankerTest,AdaptiveAgentRouterTest,EvidenceBoundAnswerTest' test
    if ($LASTEXITCODE -ne 0) { throw 'Offline tests failed; no cloud calls made.' }
    if (!$Live) { Write-Output 'DEDICATED_OFFLINE_OK: deterministic contracts only.'; return }
    foreach ($name in $names) {
        $value = [Environment]::GetEnvironmentVariable($name,'Process')
        if (!$value) { $value = [Environment]::GetEnvironmentVariable($name,'User') }
        if (!$value) { throw "Missing variable: $name" }
        [Environment]::SetEnvironmentVariable($name,$value,'Process')
    }
    & $MavenCommand -B -ntp -f backend/pom.xml dependency:build-classpath '-Dmdep.includeScope=test' '-Dmdep.outputFile=target/dedicated-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Classpath build failed.' }
    $cp = (Join-Path $root 'backend/target/test-classes') + ';' + (Join-Path $root 'backend/target/classes') + ';' +
        (Get-Content (Join-Path $root 'backend/target/dedicated-classpath.txt') -Raw -Encoding UTF8).Trim()
    $output = Join-Path $root ('evals/results/dedicated-rerank-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    & (Join-Path $JavaHome 'bin/java.exe') '-Dfile.encoding=UTF-8' -cp $cp com.ruomu.xiaozhi.service.DedicatedRerankEval (Join-Path $root 'evals/retrieval-v1.jsonl') $output
    if ($LASTEXITCODE -ne 0) { throw "Evaluation failed or incomplete; preserve report: $output" }
    Write-Output "DEDICATED_LIVE_COMPLETE: inspect evidence and regression differences in $output"
} finally {
    Pop-Location
    $env:JAVA_HOME = $oldJava
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name,$saved[$name],'Process') }
}
