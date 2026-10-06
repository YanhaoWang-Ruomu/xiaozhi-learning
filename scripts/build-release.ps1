param([string]$JavaHome=$env:JAVA_HOME,[string]$MavenCommand='mvn.cmd',[string]$NpmCommand='npm.cmd',[string]$OutputRoot='')
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$repo=Split-Path $PSScriptRoot -Parent
if($JavaHome){$env:JAVA_HOME=$JavaHome;$env:PATH="$JavaHome\bin;$env:PATH"}
if(-not $OutputRoot){$OutputRoot=Join-Path (Split-Path $repo -Parent) 'xiaozhi-learning-releases'}
$OutputRoot=[IO.Path]::GetFullPath($OutputRoot)
$repoPrefix=[IO.Path]::GetFullPath($repo).TrimEnd('\')+'\'
if($OutputRoot.StartsWith($repoPrefix,[StringComparison]::OrdinalIgnoreCase) -or $OutputRoot -eq $repo){throw 'OutputRoot must be outside the source repository'}
foreach($tool in @($MavenCommand,$NpmCommand,'git')){if(-not (Get-Command $tool -ErrorAction SilentlyContinue)){throw "Missing tool: $tool"}}
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
$commit=(& git -C $repo rev-parse --short HEAD).Trim()
if($LASTEXITCODE -ne 0){throw 'Cannot read Git revision'}
$dirty=(@(& git -C $repo status --porcelain).Count -gt 0)
$dest=Join-Path $OutputRoot "xiaozhi-$stamp-$commit"
if(Test-Path $dest){throw 'Release directory already exists'}
New-Item -ItemType Directory -Path $dest | Out-Null
try{
 Push-Location "$repo\xiaozhi-ui"
 try{
  & $NpmCommand ci
  if($LASTEXITCODE -ne 0){throw 'npm ci failed'}
  & $NpmCommand run test:ci
  if($LASTEXITCODE -ne 0){throw 'Frontend tests failed'}
  & $NpmCommand audit --audit-level=low
  if($LASTEXITCODE -ne 0){throw 'Frontend audit failed'}
  & $NpmCommand run build
  if($LASTEXITCODE -ne 0){throw 'Frontend build failed'}
 }finally{Pop-Location}
 if(-not (Test-Path "$repo\xiaozhi-ui\dist\index.html")){throw 'Vue index missing'}
 Push-Location "$repo\backend"
 try{
  & $MavenCommand '-Prelease-ui' clean verify
  if($LASTEXITCODE -ne 0){throw 'Backend release build failed'}
 }finally{Pop-Location}
 Copy-Item "$repo\backend\target\backend-1.0-SNAPSHOT.jar" "$dest\xiaozhi.jar"
 New-Item -ItemType Directory "$dest\config","$dest\sql" | Out-Null
 Copy-Item "$repo\deploy\application.properties.example" "$dest\config\application.properties"
 Copy-Item "$repo\deploy\start.ps1" "$dest\start.ps1"
 Copy-Item "$repo\docs\RELEASE.md" "$dest\README.md"
 Copy-Item "$repo\docs\BACKUP_RESTORE.md" "$dest\BACKUP_RESTORE.md"
 Copy-Item "$repo\sql\*.sql" "$dest\sql\"
 $meta=[ordered]@{builtAt=(Get-Date).ToUniversalTime().ToString('o');sourceCommit=$commit;workingTreeDirty=$dirty;scope='local single-instance';java='17';databaseIntegrationTests='not run by this script; require scripts/test-mysql.ps1 or successful CI'}
 $meta | ConvertTo-Json | Set-Content "$dest\release.json" -Encoding UTF8
 $hashes=@(Get-ChildItem $dest -Recurse -File | ForEach-Object{
  [ordered]@{path=$_.FullName.Substring($dest.Length+1).Replace('\','/');sha256=(Get-FileHash $_.FullName -Algorithm SHA256).Hash}
 })
 ConvertTo-Json -InputObject $hashes | Set-Content "$dest\manifest.json" -Encoding UTF8
 Compress-Archive -Path "$dest\*" -DestinationPath "$dest.zip"
 (Get-FileHash "$dest.zip" -Algorithm SHA256).Hash | Set-Content "$dest.zip.sha256" -Encoding ASCII
 Write-Output "RELEASE_READY=$dest"
 Write-Output "RELEASE_ZIP=$dest.zip"
}catch{Write-Output "RELEASE_FAILED_PARTIAL_DIRECTORY=$dest";throw}
