<#
.SYNOPSIS
  Permite tu IP actual en la Management UI de RabbitMQ (15672) y en Kafka UI (8080).
  crear.ps1 solo abrió esos puertos para la IP desde donde se ejecutó; si cambias
  de red (por ejemplo, la sala de clases), córrelo antes de abrir las páginas.

.EXAMPLE
  .\abrir-mi-ip.ps1
#>
param([string]$Region = 'us-east-1')
$ErrorActionPreference = 'Stop'
$env:AWS_DEFAULT_REGION = $Region
$env:AWS_PAGER = ''

function Aws {
  $ErrorActionPreference = 'Continue'
  $out = & aws.exe @args 2>&1
  if ($LASTEXITCODE -ne 0) { throw "aws $($args -join ' ') -> $($out | Out-String)" }
  return (($out | Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] }) | Out-String)
}
function Abrir($nombreSg, $puerto, $cidr) {
  $sg = (Aws ec2 describe-security-groups --filters "Name=group-name,Values=$nombreSg" --query 'SecurityGroups[0].GroupId' --output text).Trim()
  $ErrorActionPreference = 'Continue'
  $out = & aws.exe ec2 authorize-security-group-ingress --group-id $sg --ip-permissions "IpProtocol=tcp,FromPort=$puerto,ToPort=$puerto,IpRanges=[{CidrIp=$cidr}]" 2>&1
  if ($LASTEXITCODE -eq 0) { Write-Host "   $nombreSg $puerto <- $cidr (agregada)" }
  elseif ("$out" -match 'InvalidPermission.Duplicate') { Write-Host "   $nombreSg $puerto <- $cidr (ya estaba)" }
  else { throw "regla $nombreSg $puerto <- ${cidr}: $out" }
}

$miIp = (Invoke-RestMethod 'https://checkip.amazonaws.com').Trim() + '/32'
Write-Host "Mi IP: $miIp"
Abrir 'agrotrack-mq' 15672 $miIp
Abrir 'agrotrack-kafka' 8080 $miIp

$hosts = Get-Content (Join-Path (Split-Path $PSScriptRoot -Parent) '.aws-hosts.json') | ConvertFrom-Json
Write-Host "`nRabbitMQ  : http://$($hosts.mq.publica):15672"
Write-Host "Kafka UI  : http://$($hosts.kafka.publica):8080"
