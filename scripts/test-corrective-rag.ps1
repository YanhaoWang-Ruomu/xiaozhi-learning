param(
    [switch]$Live,
    [switch]$Challenge,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$MavenCommand = 'mvn.cmd'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if (!$JavaHome -or !(Test-Path (Join-Path $JavaHome 'bin/java.exe'))) { throw 'Pass -JavaHome with Java 17.' }
$oldJava = $env:JAVA_HOME
$names = if ($Challenge) { @('DASHSCOPE_API_KEY') } else { @('DASHSCOPE_API_KEY','PINECONE_API_KEY','PINECONE_INDEX_HOST') }
$saved = @{}
foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
Push-Location $root
try {
    $env:JAVA_HOME = $JavaHome
    & $MavenCommand -B -ntp -f backend/pom.xml '-Dtest=CorrectiveKnowledgeServiceTest,EvidenceBoundAnswerTest' test
    if ($LASTEXITCODE -ne 0) { throw 'Offline tests failed; no cloud calls made.' }
    if (!$Live) { Write-Output 'CORRECTIVE_OFFLINE_OK: deterministic contracts only.'; return }
    foreach ($name in $names) {
        $value = [Environment]::GetEnvironmentVariable($name,'Process')
        if (!$value) { $value = [Environment]::GetEnvironmentVariable($name,'User') }
        if (!$value) { throw "Missing variable: $name" }
        [Environment]::SetEnvironmentVariable($name,$value,'Process')
    }
    & $MavenCommand -B -ntp -f backend/pom.xml dependency:build-classpath '-Dmdep.includeScope=test' '-Dmdep.outputFile=target/corrective-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Classpath build failed.' }
    $cp = (Join-Path $root 'backend/target/test-classes') + ';' + (Join-Path $root 'backend/target/classes') + ';' +
        (Get-Content (Join-Path $root 'backend/target/corrective-classpath.txt') -Raw -Encoding UTF8).Trim()
    $prefix = if ($Challenge) { 'evidence-challenge-' } else { 'corrective-rag-' }
    $output = Join-Path $root ('evals/results/' + $prefix + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    $class = if ($Challenge) { 'com.ruomu.xiaozhi.service.EvidenceChallengeEval' } else { 'com.ruomu.xiaozhi.service.CorrectiveRagEval' }
    $dataset = if ($Challenge) { 'evals/evidence-challenge-v1.json' } else { 'evals/retrieval-v1.jsonl' }
    & (Join-Path $JavaHome 'bin/java.exe') '-Dfile.encoding=UTF-8' -cp $cp $class (Join-Path $root $dataset) $output
    if ($LASTEXITCODE -ne 0) { throw "Evaluation failed or incomplete; preserve report: $output" }
    Write-Output "CORRECTIVE_LIVE_COMPLETE: inspect evidence and regression differences in $output"
} finally {
    Pop-Location
    $env:JAVA_HOME = $oldJava
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name,$saved[$name],'Process') }
}
