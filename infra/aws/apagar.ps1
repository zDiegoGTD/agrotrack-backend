<#
.SYNOPSIS
  Detiene las tres EC2 de AgroTrack. Detenidas no consumen credito de computo;
  los discos y la IP elastica se conservan, asi que encender.ps1 lo devuelve
  todo tal cual.
#>
param([string]$Region = 'us-east-1')
$ErrorActionPreference = 'Continue'
$env:AWS_DEFAULT_REGION = $Region
$env:AWS_PAGER = ''

function Aws {
  $ErrorActionPreference = 'Continue'
  $out = & aws.exe @args 2>&1
  if ($LASTEXITCODE -ne 0) { throw "aws $($args -join ' ') -> $($out | Out-String)" }
  return (($out | Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] }) | Out-String)
}

$ids = (Aws ec2 describe-instances --filters 'Name=tag:Proyecto,Values=AgroTrack' 'Name=instance-state-name,Values=running' --query 'Reservations[].Instances[].InstanceId' --output text).Trim()
if (-not $ids) { Write-Host 'No hay instancias encendidas.'; exit 0 }

Aws ec2 stop-instances --instance-ids ($ids -split '\s+') | Out-Null
Write-Host "Deteniendo: $ids" -ForegroundColor Yellow
Write-Host 'La IP elastica de apps y los discos se conservan. Para volver: .\encender.ps1'
