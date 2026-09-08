<#
.SYNOPSIS
  Copia el codigo a una EC2 del Learner Lab por SSH y levanta un stack.

  No hace falta que la EC2 tenga acceso a GitHub (los repos son privados y
  el lab no permite crear credenciales): se sube desde el PC lo justo, sin
  node_modules ni target, y las imagenes se construyen alla.

  El compose corre en la instancia con nohup, desacoplado de la sesion SSH:
  si la conexion se corta a mitad de una construccion larga, el despliegue
  sigue y este script solo vuelve a consultar el progreso.

.PARAMETER Ip     IP publica (o elastica) de la instancia.
.PARAMETER Stack  kafka | mq | apps
.PARAMETER Env    Variables extra para el compose, p. ej. @{ EC2_KAFKA_HOST = '10.0.1.20' }
.PARAMETER Key    Ruta a la llave PEM (por defecto ~\.ssh\vockey.pem)
.PARAMETER SoloEstado  No sube nada: muestra el estado del stack.

.EXAMPLE
  .\subir.ps1 -Ip 54.1.2.3 -Stack apps
#>
param(
  [Parameter(Mandatory)] [string]$Ip,
  [Parameter(Mandatory)] [ValidateSet('kafka', 'mq', 'apps')] [string]$Stack,
  [hashtable]$Env = @{},
  [string]$Key = "$env:USERPROFILE\.ssh\vockey.pem",
  [switch]$SoloEstado
)
# Los avisos de ssh/scp van por stderr; en PS 5.1 con 'Stop' serian excepciones.
$ErrorActionPreference = 'Continue'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent    # .../AgroTrack
$ssh = @('-i', $Key, '-o', 'StrictHostKeyChecking=accept-new', '-o', 'LogLevel=ERROR', '-o', 'ServerAliveInterval=30')
$dest = "ec2-user@${Ip}"
$compose = "docker compose --env-file .env.aws -f $Stack/compose.yml"

function Remoto([string]$cmd) {
  $out = & ssh @ssh -o BatchMode=yes $dest $cmd 2>&1
  return @{ ok = ($LASTEXITCODE -eq 0); out = ($out | ForEach-Object { "$_" }) }
}

if (-not (Test-Path $Key)) { throw "No existe la llave $Key (Vocareum -> AWS Details -> Download PEM)" }

if ($SoloEstado) {
  (Remoto "cd /opt/agrotrack/infra && $compose ps && tail -n 15 /opt/agrotrack/deploy-$Stack.log").out
  exit 0
}

# Que subir segun el stack: infra siempre; los repos de codigo solo para apps
$carpetas = @('infra')
if ($Stack -eq 'apps') {
  $carpetas += Get-ChildItem $root -Directory | Where-Object { $_.Name -like 'ms-agrotrack-*' -or $_.Name -eq 'frontend-agrotrack' } | ForEach-Object Name
}

Write-Host "==> Empaquetando $($carpetas.Count) carpetas (sin node_modules/target/.git)..."
$tar = Join-Path $env:TEMP "agrotrack-$Stack.tar.gz"
$excl = @('--exclude=node_modules', '--exclude=target', '--exclude=.git', '--exclude=dist', '--exclude=.angular', '--exclude=infra/local/logs', '--exclude=salida')
Push-Location $root
try { & tar -czf $tar @excl @carpetas } finally { Pop-Location }
Write-Host ("    {0:N1} MB" -f ((Get-Item $tar).Length / 1MB))

Write-Host "==> Esperando SSH y Docker en $Ip (user-data puede seguir instalando)..."
$listo = $false
for ($i = 0; $i -lt 40 -and -not $listo; $i++) {
  $r = Remoto 'docker compose version'
  if ($r.ok) { $listo = $true } else { Start-Sleep -Seconds 10 }
}
if (-not $listo) { throw "La instancia $Ip no responde con Docker tras 6 min: revisa /var/log/cloud-init-output.log" }
Write-Host "    $($r.out)"

Write-Host "==> Copiando a $dest ..."
& scp @ssh $tar "${dest}:/tmp/agrotrack.tar.gz" 2>&1 | Where-Object { "$_" -notmatch 'Warning' } | ForEach-Object { "    $_" }
if ($LASTEXITCODE -ne 0) { throw 'scp fallo' }
if (Test-Path (Join-Path $root 'infra\.env.aws')) {
  & scp @ssh (Join-Path $root 'infra\.env.aws') "${dest}:/tmp/.env.aws" 2>&1 | Out-Null
} elseif ($Stack -eq 'apps') {
  throw "Falta infra\.env.aws (lo genera crear.ps1)"
}

$extra = ($Env.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join "`n"
$log = "/opt/agrotrack/deploy-$Stack.log"

$remoto = @"
set -e
sudo rm -rf /opt/agrotrack/infra /opt/agrotrack/ms-agrotrack-* /opt/agrotrack/frontend-agrotrack
sudo tar -xzf /tmp/agrotrack.tar.gz -C /opt/agrotrack && sudo chown -R ec2-user:ec2-user /opt/agrotrack
cd /opt/agrotrack/infra
[ -f /tmp/.env.aws ] && mv /tmp/.env.aws .env.aws
touch .env.aws
printf '%s\n' '$extra' >> .env.aws
rm -f $log
nohup bash -c '$compose up -d --build --remove-orphans; echo "__FIN__ \$?"' > $log 2>&1 < /dev/null &
echo lanzado
"@ -replace "`r", ''

Write-Host "==> Lanzando '$Stack' en la instancia (desacoplado de SSH)..."
$r = Remoto $remoto
if (-not $r.ok) { throw "No se pudo lanzar: $($r.out -join "`n")" }

Write-Host "==> Esperando a que termine (compilar las imagenes de apps toma 10-15 min la primera vez)..."
$fin = $null; $vistas = 0
for ($i = 0; $i -lt 120 -and -not $fin; $i++) {
  Start-Sleep -Seconds 15
  $t = Remoto "tail -n 200 $log"
  if (-not $t.ok) { Write-Host '    (sin conexion, reintento)'; continue }
  $lineas = @($t.out)
  $nuevas = $lineas | Where-Object { $_ -match 'DONE|Started|Healthy|Pulled|ERROR|error|__FIN__' } | Select-Object -Last 3
  if ($nuevas) { $nuevas | ForEach-Object { Write-Host "    $_" } }
  $fin = $lineas | Where-Object { $_ -match '__FIN__' } | Select-Object -Last 1
}
if (-not $fin) { throw "Sigue en curso tras 30 min. Ver: .\subir.ps1 -Ip $Ip -Stack $Stack -SoloEstado" }
if ($fin -notmatch '__FIN__ 0') {
  Write-Host "==> El compose termino con error:" -ForegroundColor Red
  (Remoto "tail -n 40 $log").out | ForEach-Object { "    $_" }
  exit 1
}

Write-Host "==> Estado:" -ForegroundColor Green
(Remoto "cd /opt/agrotrack/infra && $compose ps").out | ForEach-Object { "    $_" }
Write-Host "`nLogs: .\subir.ps1 -Ip $Ip -Stack $Stack -SoloEstado   |   ssh -i $Key $dest 'cd /opt/agrotrack/infra && $compose logs -f'"
