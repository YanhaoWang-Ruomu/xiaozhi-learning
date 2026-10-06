param([string]$JavaHome=$env:JAVA_HOME,[ValidateRange(1024,65535)][int]$Port=8081,[string]$ConfigFile=(Join-Path $PSScriptRoot 'config\application.properties'))
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$jar=Join-Path $PSScriptRoot 'xiaozhi.jar'
if(-not (Test-Path -LiteralPath $jar)){throw 'xiaozhi.jar missing; run this script from the extracted release'}
if(-not (Test-Path -LiteralPath $ConfigFile)){throw 'External configuration file missing'}
$ConfigFile=(Resolve-Path -LiteralPath $ConfigFile).Path
$java=if($JavaHome){Join-Path $JavaHome 'bin\java.exe'}else{(Get-Command java.exe -ErrorAction Stop).Source}
if(-not (Test-Path -LiteralPath $java)){throw 'Java executable missing; pass -JavaHome'}
$version=(& $java --version | Out-String)
if($version -notmatch '(?m)^(openjdk|java) 17\.'){throw 'This release requires Java 17'}
if(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue){throw "Port $Port is occupied; stop only the backend you recognize"}
$before=@{}
foreach($name in @('DASHSCOPE_API_KEY','PINECONE_API_KEY','PINECONE_INDEX_HOST','MYSQL_PASSWORD')){$before[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try{
 foreach($name in $before.Keys){
  if(-not [Environment]::GetEnvironmentVariable($name,'Process')){
   [Environment]::SetEnvironmentVariable($name,[Environment]::GetEnvironmentVariable($name,'User'),'Process')
  }
 }
 foreach($name in @('DASHSCOPE_API_KEY','PINECONE_API_KEY','PINECONE_INDEX_HOST')){
  if(-not [Environment]::GetEnvironmentVariable($name,'Process')){throw "Missing environment variable: $name"}
 }
 if(-not $env:MYSQL_PASSWORD){
  $secret=Read-Host 'MySQL application-user password (not saved)' -AsSecureString
  $ptr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
  try{$env:MYSQL_PASSWORD=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)}
  finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr);$secret.Dispose()}
  if(-not $env:MYSQL_PASSWORD){throw 'MySQL password is empty'}
 }
 Push-Location $PSScriptRoot
 try{
  Write-Output "LOCAL_URL=http://127.0.0.1:$Port/"
  Write-Output 'Keep this window open. Wait for pending work, then press Ctrl+C to stop. Databases stay running.'
  & $java '-Dfile.encoding=UTF-8' -jar $jar "--spring.config.additional-location=$([Uri]::new($ConfigFile).AbsoluteUri)" '--server.address=127.0.0.1' "--server.port=$Port" '--spring.main.web-application-type=servlet' '--xiaozhi.appointment.import-from-mongo=false' '--server.servlet.session.cookie.secure=false'
  if($LASTEXITCODE -ne 0){throw "Backend exited with code $LASTEXITCODE"}
 }finally{Pop-Location}
}finally{foreach($name in $before.Keys){[Environment]::SetEnvironmentVariable($name,$before[$name],'Process')}}
