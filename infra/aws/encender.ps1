<#
.SYNOPSIS
  Enciende AgroTrack en AWS tras un corte de sesion del Learner Lab.

  El lab detiene las EC2 cada ~4 horas. Al encenderlas, las IPs publicas de
  kafka y mq cambian (apps conserva la suya: es elastica) y Docker tarda un
  par de minutos en levantar los contenedores.

  Este script: arranca las tres instancias, espera a que respondan, actualiza
  .aws-hosts.json con las IPs nuevas y verifica que el sistema entero este en
  pie antes de devolverte el control.

.EXAMPLE
  .\encender.ps1
#>
param(
  [string]$Region = 'us-east-1',
  [string]$Key = "$env:USERPROFILE\.ssh\vockey.pem"
)
$ErrorActionPreference = 'Continue'
$env:AWS_DEFAULT_REGION = $Region
$env:AWS_PAGER = ''
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent

function Paso($t) { Write-Host "`n== $t" -ForegroundColor Cyan }
function Ok($t) { Write-Host "   OK   $t" -ForegroundColor Green }
function Aviso($t) { Write-Host "   ..   $t" -ForegroundColor Yellow }
function Falla($t) { Write-Host "   MAL  $t" -ForegroundColor Red }

function Aws {
  $ErrorActionPreference = 'Continue'
  $out = & aws.exe @args 2>&1
  if ($LASTEXITCODE -ne 0) { throw "aws $($args -join ' ') -> $($out | Out-String)" }
  return (($out | Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] }) | Out-String)
}

Paso 'Credenciales del lab'
try {
  $quien = Aws sts get-caller-identity --output json | ConvertFrom-Json
  Ok "cuenta $($quien.Account)"
} catch {
  Falla 'las credenciales caducaron o no estan puestas'
  Write-Host '   Vocareum -> AWS Details -> AWS CLI -> Show, y pega el bloque en:'
  Write-Host "   $env:USERPROFILE\.aws\credentials"
  exit 1
}

Paso 'Instancias'
$ids = @{}
foreach ($n in 'ec2-kafka', 'ec2-mq', 'ec2-apps') {
  $id = (Aws ec2 describe-instances --filters "Name=tag:Name,Values=$n" 'Name=instance-state-name,Values=pending,running,stopping,stopped' --query 'Reservations[0].Instances[0].InstanceId' --output text).Trim()
  if (-not $id -or $id -eq 'None') { Falla "$n no existe: ejecuta crear.ps1"; exit 1 }
  $estado = (Aws ec2 describe-instances --instance-ids $id --query 'Reservations[0].Instances[0].State.Name' --output text).Trim()
  $ids[$n] = $id
  if ($estado -eq 'running') { Ok "$n ya estaba encendida" }
  else { Aws ec2 start-instances --instance-ids $id | Out-Null; Aviso "$n arrancando ($estado)" }
}

Aws ec2 wait instance-running --instance-ids $ids.Values | Out-Null
Ok 'las tres en running'

Paso 'IPs (las publicas de kafka y mq cambian en cada arranque)'
$hosts = [ordered]@{ region = $Region }
foreach ($n in 'ec2-kafka', 'ec2-mq', 'ec2-apps') {
  $j = Aws ec2 describe-instances --instance-ids $ids[$n] --query 'Reservations[0].Instances[0].[PublicIpAddress,PrivateIpAddress]' --output json | ConvertFrom-Json
  $clave = $n -replace '^ec2-', ''
  $hosts[$clave] = @{ id = $ids[$n]; publica = $j[0]; privada = $j[1] }
  Write-Host ("   {0,-6} publica {1,-16} privada {2}" -f $clave, $j[0], $j[1])
}
$hostsFile = Join-Path $root 'infra\.aws-hosts.json'
$previo = if (Test-Path $hostsFile) { Get-Content $hostsFile -Raw | ConvertFrom-Json } else { $null }
if ($previo.gateway) { $hosts['gateway'] = $previo.gateway }
$hosts | ConvertTo-Json -Depth 3 | Set-Content $hostsFile -Encoding UTF8
Ok "$hostsFile actualizado"

# Las privadas no cambian al reiniciar, pero si cambiaran, .env.aws quedaria
# apuntando a la nada y los servicios no encontrarian sus brokers.
$envFile = Join-Path $root 'infra\.env.aws'
if (Test-Path $envFile) {
  $env_ = Get-Content $envFile -Raw
  $desfase = ($env_ -notmatch [regex]::Escape($hosts.mq.privada)) -or ($env_ -notmatch [regex]::Escape($hosts.kafka.privada))
  if ($desfase) {
    Falla '.env.aws apunta a IPs privadas distintas de las actuales'
    Write-Host '   Corrige RABBITMQ_HOST y KAFKA_BOOTSTRAP y redespliega apps.'
  } else { Ok '.env.aws coincide con las IPs privadas actuales' }
}

Paso 'Esperando a Docker (2-3 min)'
$ssh = @('-i', $Key, '-o', 'StrictHostKeyChecking=accept-new', '-o', 'LogLevel=ERROR', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=8')
foreach ($n in 'kafka', 'mq', 'apps') {
  $ip = $hosts[$n].publica
  # La IP cambio: la clave de host vieja ya no vale
  & ssh-keygen -R $ip 2>&1 | Out-Null
  $vivo = $false
  for ($i = 0; $i -lt 40 -and -not $vivo; $i++) {
    $r = & ssh @ssh "ec2-user@$ip" 'docker ps --format "{{.Names}}" | wc -l' 2>&1
    if ($LASTEXITCODE -eq 0 -and [int]"$r".Trim() -gt 0) { $vivo = $true; Ok "$n con $("$r".Trim()) contenedores" }
    else { Start-Sleep -Seconds 10 }
  }
  if (-not $vivo) { Falla "$n no levanto contenedores: ssh -i $Key ec2-user@$ip 'docker ps -a'" }
}

Paso 'Comprobaciones de extremo a extremo'
$gw = $hosts['gateway']
$appsIp = $hosts.apps.publica
function Http($url, $esperado, $texto) {
  try { $c = (Invoke-WebRequest $url -UseBasicParsing -TimeoutSec 20 -ErrorAction Stop).StatusCode }
  catch { $c = $_.Exception.Response.StatusCode.value__ }
  if ($c -eq $esperado) { Ok "$texto ($c)" } else { Falla "$texto: esperaba $esperado, llego $c" }
}
Http "http://${appsIp}:8081/actuator/health" 200 'BFF sano'
if ($gw) {
  Http "$gw/" 200 'aplicacion por HTTPS'
  Http "$gw/api/me" 401 'API Gateway rechaza sin token'
}

$estado = & ssh @ssh "ec2-user@$($hosts.apps.publica)" 'docker ps --format "{{.Names}}: {{.Status}}" | sort' 2>&1
Write-Host "`n   Contenedores en apps:"; $estado | ForEach-Object { Write-Host "     $_" }

Write-Host "`nAplicacion: $gw" -ForegroundColor Green
Write-Host "RabbitMQ  : http://$($hosts.mq.publica):15672   Kafka UI: http://$($hosts.kafka.publica):8080"
Write-Host "`nAl terminar de trabajar:  .\apagar.ps1" -ForegroundColor Yellow
