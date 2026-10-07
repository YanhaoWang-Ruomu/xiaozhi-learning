param([string]$JavaHome=$env:JAVA_HOME,[string]$MavenCommand='mvn.cmd')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME=$JavaHome
foreach($name in @('DASHSCOPE_API_KEY','PINECONE_API_KEY','PINECONE_INDEX_HOST')) {
 if(-not [Environment]::GetEnvironmentVariable($name,'Process')) {
  [Environment]::SetEnvironmentVariable($name,[Environment]::GetEnvironmentVariable($name,'User'),'Process')
 }
 if(-not [Environment]::GetEnvironmentVariable($name,'Process')) { throw "Missing $name" }
}
Push-Location "$repo/backend"
try {
 & $MavenCommand '-q' '-DskipTests' 'test-compile' 'dependency:build-classpath' '-Dmdep.outputFile=target/engineering-classpath.txt' '-Dmdep.includeScope=test'
 if($LASTEXITCODE -ne 0){throw 'Evaluation compilation failed'}
 $cp='target/classes;target/test-classes;'+(Get-Content -Raw -Encoding UTF8 target/engineering-classpath.txt).Trim()
 $out=Join-Path $repo ('evals/results/comparison-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
 New-Item -ItemType Directory $out -Force | Out-Null
 foreach($mode in @('vector','hybrid','llm')) {
  & "$JavaHome/bin/java.exe" '-cp' $cp 'com.ruomu.xiaozhi.eval.EngineeringEval' "$repo/evals/retrieval-v1.jsonl" "$out/$mode" $mode
  if($LASTEXITCODE -ne 0){throw "Evaluation failed for $mode; inspect partial report $out"}
 }
 Write-Output "ENGINEERING_REPORT=$out"
} finally {Pop-Location}
