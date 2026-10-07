param(
    [switch]$Live,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$MavenCommand = 'mvn.cmd'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$oldJavaHome = $env:JAVA_HOME
$oldPath = $env:PATH
Push-Location $root
try {
    if ([string]::IsNullOrWhiteSpace($JavaHome) -or !(Test-Path (Join-Path $JavaHome 'bin/java.exe'))) {
        throw 'Set JAVA_HOME or pass -JavaHome with a JDK 17 path.'
    }
    $env:JAVA_HOME = $JavaHome
    $env:PATH = (Join-Path $JavaHome 'bin') + ';' + $env:PATH
    & $MavenCommand -B -ntp -f backend/pom.xml '-Dtest=RetrievalEvalTest' test
    if ($LASTEXITCODE -ne 0) { throw 'Offline evaluator tests failed; no cloud requests made.' }
    if (!$Live) {
        Write-Output 'EVAL_OFFLINE_OK: dataset and grader only; no AI quality result.'
        return
    }
    foreach ($name in @('DASHSCOPE_API_KEY','PINECONE_API_KEY','PINECONE_INDEX_HOST')) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
            throw "Missing process environment variable: $name. Reopen the terminal after configuring it."
        }
    }
    & $MavenCommand -B -ntp -f backend/pom.xml dependency:build-classpath '-Dmdep.includeScope=test' '-Dmdep.outputFile=target/eval-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Could not build evaluation classpath.' }
    $cp = (Join-Path $root 'backend/target/test-classes') + ';' + (Join-Path $root 'backend/target/classes') + ';' +
          (Get-Content (Join-Path $root 'backend/target/eval-classpath.txt') -Raw -Encoding UTF8).Trim()
    $head = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Could not identify Git revision.' }
    $dirty = [bool](& git status --porcelain)
    $output = Join-Path $root ('backend/target/eval-runs/' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8))
    New-Item -ItemType Directory -Force -Path (Split-Path $output -Parent) | Out-Null
    & (Join-Path $JavaHome 'bin/java.exe') '-Dfile.encoding=UTF-8' -cp $cp com.ruomu.xiaozhi.eval.RetrievalEval live (Join-Path $root 'evals/retrieval-v1.jsonl') $output $head $dirty.ToString().ToLowerInvariant()
    if ($LASTEXITCODE -ne 0) { throw "Live retrieval evaluation incomplete. Inspect report if created: $output" }
    Write-Output 'EVAL_LIVE_COMPLETE: baseline collected, not a model-answer quality pass.'
} finally {
    Pop-Location
    $env:JAVA_HOME = $oldJavaHome
    $env:PATH = $oldPath
}
