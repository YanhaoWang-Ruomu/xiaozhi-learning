param(
 [string]$JavaHome='F:\xiaozhi-medical\tools\jdk\jdk-17.0.20.1+1',
 [string]$MavenCommand='F:\xiaozhi-medical\tools\maven\apache-maven-3.9.11\bin\mvn.cmd',
 [string]$NodeCommand='F:\Node.js\node.exe',
 [string]$MySqlHome='C:\Program Files\MySQL\MySQL Server 8.4',
 [string]$MongoServer='F:\xiaozhi-medical\tools\mongodb\mongodb-win32-x86_64-windows-8.0.6\bin\mongod.exe',
 [string]$MongoTools='F:\xiaozhi-medical\tools\mongodb-database-tools-100.19.1\mongodb-database-tools-windows-x86_64-100.19.1\bin',
 [string]$WorkRoot='F:\xiaozhi-learning-backups'
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$repo=Split-Path $PSScriptRoot -Parent
$work=Join-Path $WorkRoot ('backup-drill-'+[guid]::NewGuid().ToString('N'))
$java=Join-Path $JavaHome 'bin\java.exe'
$mysql=Join-Path $MySqlHome 'bin\mysql.exe'
$mysqld=Join-Path $MySqlHome 'bin\mysqld.exe'
$mysqladmin=Join-Path $MySqlHome 'bin\mysqladmin.exe'
$mysqldump=Join-Path $MySqlHome 'bin\mysqldump.exe'
foreach($file in @($java,$MavenCommand,$NodeCommand,$mysql,$mysqld,$mysqladmin,$mysqldump,$MongoServer,"$MongoTools\mongodump.exe","$MongoTools\mongorestore.exe")){
 if(-not (Test-Path -LiteralPath $file)){throw "Required tool missing: $file"}
}
foreach($port in @(13307,13308,27317,27318,18081,18082)){
 if(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue){throw "Isolated port occupied: $port"}
}
New-Item -ItemType Directory $work | Out-Null
$owned=@()
$passed=$false
function Launch([string]$exe,[array]$arguments,[string]$name){
 $quoted=@($arguments | ForEach-Object {'"'+([string]$_).Replace('"','\"')+'"'})
 $p=Start-Process -FilePath $exe -ArgumentList $quoted -WorkingDirectory $work -PassThru -WindowStyle Hidden -RedirectStandardOutput "$work\$name.out.log" -RedirectStandardError "$work\$name.err.log"
 $null=$p.Handle
 $script:owned+=@{pid=$p.Id;name=$name;start=$p.StartTime.ToUniversalTime().ToString('o')}
 $script:owned | ConvertTo-Json | Set-Content "$work\owned-processes.json"
 return $p
}
function Run([string]$exe,[array]$arguments,[string]$name){
 $p=Launch $exe $arguments $name
 if(-not $p.WaitForExit(300000)){throw "Step timed out: $name"}
 if($p.ExitCode -ne 0){throw "Step failed: $name exit=$($p.ExitCode); inspect $work"}
 Write-Output "STEP_OK=$name"
}
function WaitPort([int]$port,$p){
 for($i=0;$i -lt 150;$i++){
  if($p.HasExited){throw "Process exited before port $port opened"}
  $tcp=New-Object Net.Sockets.TcpClient
  try{$tcp.Connect('127.0.0.1',$port);return}catch{}finally{$tcp.Dispose()}
  Start-Sleep -Milliseconds 400
 }
 throw "Port not ready: $port"
}
function StartDb([string]$side,[int]$sqlPort,[int]$mongoPort){
 $data="$work\$side-mysql";$mongoData="$work\$side-mongo"
 New-Item -ItemType Directory $data,$mongoData | Out-Null
 Run $mysqld @('--no-defaults','--initialize-insecure',"--basedir=$MySqlHome","--datadir=$data") "$side-init"
 $p=Launch $mysqld @('--no-defaults','--console',"--port=$sqlPort",'--bind-address=127.0.0.1','--mysqlx=0','--innodb-buffer-pool-size=64M','--max-connections=30',"--datadir=$data") "$side-mysql"
 WaitPort $sqlPort $p
 $m=Launch $MongoServer @('--bind_ip','127.0.0.1','--port',$mongoPort,'--dbpath',$mongoData) "$side-mongo"
 WaitPort $mongoPort $m
}
function SqlArgs([int]$port){return @('--no-defaults','--protocol=tcp','--host=127.0.0.1',"--port=$port",'--user=root','--skip-password','--default-character-set=utf8mb4')}
function AppArgs([int]$sqlPort,[int]$mongoPort){
 return @('-Dfile.encoding=UTF-8','-jar',"$repo\backend\target\backend-1.0-SNAPSHOT.jar",
 "--spring.datasource.url=jdbc:mysql://127.0.0.1:$sqlPort/xiaozhi_learning?sslMode=DISABLED&characterEncoding=UTF-8&connectionTimeZone=Asia/Shanghai",
 '--spring.datasource.username=root','--spring.datasource.password=',"--spring.data.mongodb.uri=mongodb://127.0.0.1:$mongoPort/xiaozhi_learning",
 '--server.address=127.0.0.1','--server.servlet.session.cookie.secure=false')
}
function StopOwned($p){
 if(-not $p.HasExited){Stop-Process -Id $p.Id -Force;$p.WaitForExit(15000)|Out-Null}
}
function Snapshot([string]$mode,[int]$sqlPort,[int]$mongoPort,[string]$file,[string]$name){
 Run $java @('-Dfile.encoding=UTF-8','-cp',$script:classpath,"$repo\scripts\BackupRestoreSnapshot.java",$mode,$sqlPort,$mongoPort,$file) $name
}
try{
 $env:JAVA_HOME=$JavaHome
 # No cloud calls are part of this drill; never inherit real model credentials.
 $env:DASHSCOPE_API_KEY='test-only-no-cloud';$env:PINECONE_API_KEY='test-only-no-cloud';$env:PINECONE_INDEX_HOST='test-only.pinecone.io'
 $env:MYSQL_PASSWORD='';$env:MYSQL_USERNAME='root'
 Push-Location "$repo\backend"
 try{
  & $MavenCommand '-DskipTests' package dependency:build-classpath "-Dmdep.outputFile=$work\classpath.txt" *> "$work\build.log"
  if($LASTEXITCODE -ne 0){throw 'Build/classpath failed'}
 }finally{Pop-Location}
 $script:classpath="$repo\backend\target\classes;"+[IO.File]::ReadAllText("$work\classpath.txt",[Text.Encoding]::UTF8).Trim()
 StartDb 'source' 13307 27317
 Run $mysql ((SqlArgs 13307)+@('--execute=CREATE DATABASE xiaozhi_learning CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;')) 'create-source-db'
 foreach($file in Get-ChildItem "$repo\sql\*.sql" | Sort-Object Name){
  Run $mysql ((SqlArgs 13307)+@("--execute=source $($file.FullName.Replace('\','/'));")) ('schema-'+$file.BaseName)
 }
 Run $java ((AppArgs 13307 27317)+@('--spring.main.web-application-type=none','--xiaozhi.appointment.import-from-mongo=true')) 'source-import'
 $app=Launch $java ((AppArgs 13307 27317)+@('--server.port=18081')) 'source-app'
 WaitPort 18081 $app
 Run $NodeCommand @("$repo\scripts\backup-restore-probe.mjs",'seed','18081',"$work\fixture.json") 'seed-api'
 StopOwned $app
 Snapshot 'seed-memory' 13307 27317 "$work\fixture.json" 'seed-history'
 Snapshot 'snapshot' 13307 27317 "$work\source.json" 'snapshot-source'
 Run $mysqldump ((SqlArgs 13307)+@('--single-transaction','--quick','--no-tablespaces','--set-gtid-purged=OFF',"--result-file=$work\mysql.sql",'--databases','xiaozhi_learning')) 'dump-mysql'
 Run "$MongoTools\mongodump.exe" @('--uri=mongodb://127.0.0.1:27317','--db=xiaozhi_learning',"--archive=$work\mongo.archive.gz",'--gzip') 'dump-mongo'
 Snapshot 'snapshot' 13307 27317 "$work\source-after-dump.json" 'snapshot-source-after'
 if((Get-FileHash "$work\source.json").Hash -ne (Get-FileHash "$work\source-after-dump.json").Hash){throw 'Source changed during paired backup'}
 $hashes=@(Get-Item "$work\mysql.sql","$work\mongo.archive.gz" | ForEach-Object {
  [ordered]@{file=$_.Name;bytes=$_.Length;sha256=(Get-FileHash $_.FullName -Algorithm SHA256).Hash}
 })
 foreach($h in $hashes){if($h.bytes -le 0){throw 'Empty backup file'}}
 $hashes | ConvertTo-Json | Set-Content "$work\backup-manifest.json" -Encoding UTF8
 StartDb 'target' 13308 27318
 foreach($h in $hashes){if((Get-FileHash (Join-Path $work $h.file) -Algorithm SHA256).Hash -ne $h.sha256){throw 'Backup hash mismatch'}}
 Run $mysql ((SqlArgs 13308)+@("--execute=source $($work.Replace('\','/'))/mysql.sql;")) 'restore-mysql'
 Run "$MongoTools\mongorestore.exe" @('--uri=mongodb://127.0.0.1:27318',"--archive=$work\mongo.archive.gz",'--gzip','--nsInclude=xiaozhi_learning.*','--stopOnError') 'restore-mongo'
 Snapshot 'snapshot' 13308 27318 "$work\restored.json" 'snapshot-restored'
 if((Get-FileHash "$work\source.json").Hash -ne (Get-FileHash "$work\restored.json").Hash){throw 'Restored rows, schema, documents or indexes differ'}
 Write-Output 'EXACT_RESTORE_SNAPSHOT_OK'
 $restoredApp=Launch $java ((AppArgs 13308 27318)+@('--server.port=18082')) 'target-app'
 WaitPort 18082 $restoredApp
 Run $NodeCommand @("$repo\scripts\backup-restore-probe.mjs",'verify','18082',"$work\fixture.json") 'verify-api'
 StopOwned $restoredApp
 $passed=$true
 Write-Output 'BACKUP_RESTORE_DRILL_OK'
}catch{
 $_.Exception.Message | Set-Content "$work\failure.txt" -Encoding UTF8
 throw
}finally{
 $cleanupOK=$true
 foreach($item in $owned){
  if($item.name -in @('source-mysql','target-mysql')){continue}
  $p=Get-Process -Id $item.pid -ErrorAction SilentlyContinue
  if($p -and $p.StartTime.ToUniversalTime().ToString('o') -eq $item.start){StopOwned $p}
 }
 foreach($pair in @(@('source',13307),@('target',13308))){
  $side=$pair[0];$port=$pair[1]
  $listener=Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue
  if($listener){
   $info=Get-CimInstance Win32_Process -Filter ('ProcessId='+$listener.OwningProcess)
   if($info.CommandLine -notlike "*$work\$side-mysql*"){$cleanupOK=$false;Write-Warning "Unknown listener $port left untouched";continue}
   $db=Get-Process -Id $listener.OwningProcess;$null=$db.Handle
   $shutdown=Start-Process -FilePath $mysqladmin -ArgumentList @('--no-defaults','--protocol=tcp','--host=127.0.0.1',"--port=$port",'--user=root','--skip-password','shutdown') -PassThru -WindowStyle Hidden -RedirectStandardOutput "$work\$side-shutdown.out.log" -RedirectStandardError "$work\$side-shutdown.err.log"
   $null=$shutdown.Handle
   if(-not $shutdown.WaitForExit(30000)){$cleanupOK=$false}
   elseif($shutdown.ExitCode -ne 0 -or -not $db.WaitForExit(20000)){$cleanupOK=$false}
  }
 }
 if($cleanupOK){
  foreach($name in @('source-mysql','source-mongo','target-mysql','target-mongo')){
   $path=Join-Path $work $name;if(Test-Path $path){Remove-Item -LiteralPath $path -Recurse -Force}
  }
 }
 if(Test-Path "$work\fixture.json"){Remove-Item "$work\fixture.json" -Force}
 [ordered]@{passed=$passed;cleanupOK=$cleanupOK;finishedAt=(Get-Date).ToUniversalTime().ToString('o');scope='isolated synthetic fixtures only';sourcePorts=@(13307,27317);targetPorts=@(13308,27318)} | ConvertTo-Json | Set-Content "$work\result.json" -Encoding UTF8
 Write-Output "DRILL_LOGS=$work"
 if(-not $cleanupOK){throw 'Drill cleanup incomplete; inspect owned-processes.json'}
}
