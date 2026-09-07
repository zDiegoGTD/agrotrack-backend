<#
.SYNOPSIS
  Copia el codigo a una EC2 del Learner Lab por SSH y levanta un stack.

  No hace falta que la EC2 tenga acceso a GitHub (los repos son privados y
  el lab no permite crear credenciales): se sube desde el PC lo justo, sin
  node_modules ni target, y las imagenes se construyen alla.

.PARAMETER Ip     IP publica (o elastica) de la instancia.
.PARAMETER Stack  kafka | mq | apps
.PARAMETER Env    Variables extra para el compose, p. ej. @{ EC2_KAFKA_HOST = '10.0.1.20' }
.PARAMETER Key    Ruta a la llave PEM (por defecto ~\.ssh\vockey.pem)

.EXAMPLE
  .\subir.ps1 -Ip 54.1.2.3 -Stack apps
#>
param(
  [Parameter(Mandatory)] [string]$Ip,
  [Parameter(Mandatory)] [ValidateSet('kafka', 'mq', 'apps')] [string]$Stack,
  [hashtable]$Env = @{},
  [string]$Key = "$env:USERPROFILE\.ssh\vockey.pem"
)
$ErrorActionPreference = 'Stop'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent    # .../AgroTrack
$ssh = @('-i', $Key, '-o', 'StrictHostKeyChecking=accept-new')
$dest = "ec2-user@${Ip}"

if (-not (Test-Path $Key)) { throw "No existe la llave $Key (Vocareum -> AWS Details -> Download PEM)" }

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

Write-Host "==> Copiando a $dest ..."
& scp @ssh $tar "${dest}:/tmp/agrotrack.tar.gz"
if (Test-Path (Join-Path $root 'infra\.env.aws')) {
  & scp @ssh (Join-Path $root 'infra\.env.aws') "${dest}:/tmp/.env.aws"
} elseif ($Stack -eq 'apps') {
  throw "Falta infra\.env.aws (copiar de .env.aws.example y completar)"
}

# Variables extra -> archivo de entorno del stack
$extra = ($Env.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join "`n"

$remoto = @"
set -e
sudo rm -rf /opt/agrotrack/* && sudo tar -xzf /tmp/agrotrack.tar.gz -C /opt/agrotrack && sudo chown -R ec2-user:ec2-user /opt/agrotrack
cd /opt/agrotrack/infra
[ -f /tmp/.env.aws ] && mv /tmp/.env.aws .env.aws
touch .env.aws
printf '%s\n' '$extra' >> .env.aws
echo '==> docker compose ($Stack)'
docker compose --env-file .env.aws -f $Stack/compose.yml up -d --build --remove-orphans
docker compose --env-file .env.aws -f $Stack/compose.yml ps
"@ -replace "`r", ''

Write-Host "==> Levantando stack '$Stack' en la instancia..."
& ssh @ssh $dest $remoto
Write-Host "`nListo. Logs: ssh -i $Key $dest 'cd /opt/agrotrack/infra && docker compose --env-file .env.aws -f $Stack/compose.yml logs -f'"
