<#
.SYNOPSIS
  Crea toda la infraestructura de AgroTrack en AWS Academy con la AWS CLI:
  3 security groups, 3 EC2 (kafka, mq, apps) con Docker via user-data,
  Elastic IP para apps, y deja infra/.env.aws e infra/.aws-hosts.json listos
  para subir.ps1. Idempotente: si algo ya existe con ese nombre, lo reutiliza.

  Requiere: aws cli con credenciales del lab en ~/.aws/credentials, y la
  llave vockey.pem en ~/.ssh.

.EXAMPLE
  .\crear.ps1 -TenantId <TENANT_ID> -ClientId <CLIENT_ID>
#>
param(
  [Parameter(Mandatory)] [string]$TenantId,
  [Parameter(Mandatory)] [string]$ClientId,
  [string]$Region = 'us-east-1',
  [string]$KeyName = 'vockey'
)
$ErrorActionPreference = 'Stop'
$env:AWS_DEFAULT_REGION = $Region
$env:AWS_PAGER = ''
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent

function Paso($t) { Write-Host "`n== $t" -ForegroundColor Cyan }
function Aws { $out = & aws @args 2>&1; if ($LASTEXITCODE -ne 0) { throw "aws $($args -join ' ') -> $out" }; return ($out | Out-String) }

Paso 'Credenciales'
$quien = Aws sts get-caller-identity --output json | ConvertFrom-Json
Write-Host "   cuenta $($quien.Account) como $($quien.Arn)"

Paso 'VPC por defecto y mi IP'
$vpc = (Aws ec2 describe-vpcs --filters Name=isDefault,Values=true --query 'Vpcs[0].VpcId' --output text).Trim()
$subnet = (Aws ec2 describe-subnets --filters "Name=vpc-id,Values=$vpc" "Name=default-for-az,Values=true" --query 'Subnets[0].SubnetId' --output text).Trim()
$miIp = (Invoke-RestMethod 'https://checkip.amazonaws.com').Trim() + '/32'
Write-Host "   vpc $vpc, subnet $subnet, mi IP $miIp"

function Sg($nombre, $descripcion) {
  $id = (Aws ec2 describe-security-groups --filters "Name=group-name,Values=$nombre" "Name=vpc-id,Values=$vpc" --query 'SecurityGroups[0].GroupId' --output text).Trim()
  if ($id -eq 'None' -or -not $id) {
    $id = (Aws ec2 create-security-group --group-name $nombre --description $descripcion --vpc-id $vpc --query 'GroupId' --output text).Trim()
    Write-Host "   creado $nombre ($id)"
  } else { Write-Host "   existe $nombre ($id)" }
  return $id
}
function Regla($sg, $desde, $hasta, $origen) {
  # origen: CIDR o id de otro SG
  $src = if ($origen -like 'sg-*') { "UserIdGroupPairs=[{GroupId=$origen}]" } else { "IpRanges=[{CidrIp=$origen}]" }
  $out = & aws ec2 authorize-security-group-ingress --group-id $sg --ip-permissions "IpProtocol=tcp,FromPort=$desde,ToPort=$hasta,$src" 2>&1
  if ($LASTEXITCODE -ne 0 -and "$out" -notmatch 'InvalidPermission.Duplicate') { throw "regla $sg ${desde}-${hasta} <- ${origen}: $out" }
}

Paso 'Security groups'
$sgApps  = Sg 'sg-agrotrack-apps'  'AgroTrack apps: frontend, BFF'
$sgMq    = Sg 'sg-agrotrack-mq'    'AgroTrack RabbitMQ'
$sgKafka = Sg 'sg-agrotrack-kafka' 'AgroTrack Kafka'
Regla $sgApps 22 22 $miIp;      Regla $sgApps 80 80 '0.0.0.0/0'; Regla $sgApps 8081 8081 '0.0.0.0/0'
Regla $sgMq 22 22 $miIp;        Regla $sgMq 5672 5672 $sgApps;  Regla $sgMq 15672 15672 $miIp
Regla $sgMq 4369 4369 $sgMq;    Regla $sgMq 25672 25672 $sgMq
Regla $sgKafka 22 22 $miIp;     Regla $sgKafka 9092 9094 $sgApps; Regla $sgKafka 8080 8080 $miIp
Write-Host '   reglas aplicadas'

Paso 'AMI Amazon Linux 2023'
$ami = (Aws ssm get-parameter --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-6.1-x86_64 --query 'Parameter.Value' --output text).Trim()
Write-Host "   $ami"

$userData = Join-Path $PSScriptRoot 'user-data.sh'
function Instancia($nombre, $tipo, $sg, $discoGb) {
  $id = (Aws ec2 describe-instances --filters "Name=tag:Name,Values=$nombre" 'Name=instance-state-name,Values=pending,running,stopping,stopped' --query 'Reservations[0].Instances[0].InstanceId' --output text).Trim()
  if ($id -and $id -ne 'None') {
    $estado = (Aws ec2 describe-instances --instance-ids $id --query 'Reservations[0].Instances[0].State.Name' --output text).Trim()
    Write-Host "   existe $nombre ($id, $estado)"
    if ($estado -eq 'stopped') { Aws ec2 start-instances --instance-ids $id | Out-Null; Write-Host '   arrancando...' }
    return $id
  }
  $id = (Aws ec2 run-instances --image-id $ami --instance-type $tipo --key-name $KeyName --security-group-ids $sg --subnet-id $subnet `
      --user-data "file://$userData" `
      --block-device-mappings "DeviceName=/dev/xvda,Ebs={VolumeSize=$discoGb,VolumeType=gp3}" `
      --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$nombre},{Key=Proyecto,Value=AgroTrack}]" `
      --query 'Instances[0].InstanceId' --output text).Trim()
  Write-Host "   creada $nombre ($id, $tipo, $discoGb GB)"
  return $id
}

Paso 'Instancias'
$idKafka = Instancia 'ec2-kafka' 't3.large' $sgKafka 20
$idMq    = Instancia 'ec2-mq'    't3.small' $sgMq    10
$idApps  = Instancia 'ec2-apps'  't3.large' $sgApps  30

Paso 'Esperando a que esten running'
Aws ec2 wait instance-running --instance-ids $idKafka $idMq $idApps | Out-Null
Write-Host '   las tres running'

Paso 'Elastic IP para apps'
$eip = (Aws ec2 describe-addresses --filters "Name=instance-id,Values=$idApps" --query 'Addresses[0].PublicIp' --output text).Trim()
if (-not $eip -or $eip -eq 'None') {
  $libre = (Aws ec2 describe-addresses --query 'Addresses[?AssociationId==null].AllocationId | [0]' --output text).Trim()
  if (-not $libre -or $libre -eq 'None') { $libre = (Aws ec2 allocate-address --domain vpc --query 'AllocationId' --output text).Trim() }
  Aws ec2 associate-address --instance-id $idApps --allocation-id $libre | Out-Null
  $eip = (Aws ec2 describe-addresses --allocation-ids $libre --query 'Addresses[0].PublicIp' --output text).Trim()
}
Write-Host "   apps -> $eip"

function Ips($id) {
  $j = Aws ec2 describe-instances --instance-ids $id --query 'Reservations[0].Instances[0].[PublicIpAddress,PrivateIpAddress]' --output json | ConvertFrom-Json
  return @{ publica = $j[0]; privada = $j[1] }
}
$kafka = Ips $idKafka; $mq = Ips $idMq; $apps = Ips $idApps

$hosts = [ordered]@{
  region = $Region
  kafka  = @{ id = $idKafka; publica = $kafka.publica; privada = $kafka.privada }
  mq     = @{ id = $idMq;    publica = $mq.publica;    privada = $mq.privada }
  apps   = @{ id = $idApps;  publica = $eip;           privada = $apps.privada }
}
$hostsFile = Join-Path $root 'infra\.aws-hosts.json'
$hosts | ConvertTo-Json -Depth 3 | Set-Content $hostsFile -Encoding UTF8

Paso '.env.aws'
$envFile = Join-Path $root 'infra\.env.aws'
@"
# Generado por infra/aws/crear.ps1 el $(Get-Date -Format s). Regenerar si cambian las IPs.
AZURE_TENANT_ID=$TenantId
AZURE_API_CLIENT_ID=$ClientId
AZURE_ISSUER_URI=https://login.microsoftonline.com/$TenantId/v2.0

POSTGRES_HOST=postgres
POSTGRES_USER=agrotrack
POSTGRES_PASSWORD=CambiaEsto123
RABBITMQ_HOST=$($mq.privada)
KAFKA_BOOTSTRAP=$($kafka.privada):9092,$($kafka.privada):9093,$($kafka.privada):9094
KAFKA_REPLICAS=3
EC2_KAFKA_HOST=$($kafka.privada)

POSTGRES_DELIVERIES_PASSWORD=agro_deliveries
POSTGRES_CATALOG_PASSWORD=agro_catalog
POSTGRES_AUDIT_PASSWORD=agro_audit
POSTGRES_REPORT_PASSWORD=agro_report
RABBITMQ_USER=agrotrack
RABBITMQ_PASSWORD=CambiaEsto123
RABBITMQ_ERLANG_COOKIE=cookie-agrotrack-aws

CORS_ORIGENES=http://$eip
"@ | Set-Content $envFile -Encoding UTF8
Write-Host "   $envFile"

Write-Host "`n=================== RESUMEN ===================" -ForegroundColor Green
Write-Host ("kafka  publica {0,-16} privada {1}" -f $kafka.publica, $kafka.privada)
Write-Host ("mq     publica {0,-16} privada {1}" -f $mq.publica, $mq.privada)
Write-Host ("apps   elastica {0,-15} privada {1}" -f $eip, $apps.privada)
Write-Host "Guardado en $hostsFile"
Write-Host "`nSiguiente (esperar ~2 min a que user-data instale Docker):"
Write-Host "  .\subir.ps1 -Ip $($kafka.publica) -Stack kafka"
Write-Host "  .\subir.ps1 -Ip $($mq.publica) -Stack mq"
Write-Host "  .\subir.ps1 -Ip $eip -Stack apps"
Write-Host "  .\gateway.ps1 -TenantId $TenantId -ClientId $ClientId"
