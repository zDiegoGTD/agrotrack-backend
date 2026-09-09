<#
.SYNOPSIS
  Arranca los 8 servicios en el PC validando tokens REALES de Azure AD
  (en vez de la clave de desarrollo) y prepara el frontend en modo MSAL.
  Sirve para probar la identidad completa antes de desplegar en AWS.

  Requiere: infra/local/compose.yml levantado y los jars empaquetados.

.EXAMPLE
  .\azure-local.ps1 -TenantId 1111-... -ClientId 2222-...
#>
param(
  [Parameter(Mandatory)] [string]$TenantId,
  [Parameter(Mandatory)] [string]$ClientId
)
$ErrorActionPreference = 'Stop'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
$logs = Join-Path $PSScriptRoot 'logs'; New-Item -ItemType Directory -Force $logs | Out-Null

# Libera los puertos de los servicios: un java zombi de una corrida anterior
# haria fallar el arranque con "Port already in use" sin que se note.
foreach ($puerto in 8081..8088) {
  Get-NetTCPConnection -LocalPort $puerto -State Listen -ErrorAction SilentlyContinue | ForEach-Object {
    try { Stop-Process -Id $_.OwningProcess -Force -ErrorAction Stop; Write-Host "   liberado :$puerto (pid $($_.OwningProcess))" } catch { }
  }
}



$issuer = "https://login.microsoftonline.com/$TenantId/v2.0"
$jwks   = "https://login.microsoftonline.com/$TenantId/discovery/v2.0/keys"

# jwk-set-uri tiene prioridad sobre public-key-location en Spring Boot: asi se
# reemplaza la clave local sin tocar ningun archivo de los servicios.
$env:SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI = $jwks
$env:SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI  = $issuer
$env:SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES   = "api://$ClientId,$ClientId"

$servicios = [ordered]@{
  'ms-agrotrack-mq-admin' = 8087; 'ms-agrotrack-kafka-admin' = 8088
  'ms-agrotrack-catalog' = 8083;  'ms-agrotrack-deliveries' = 8082
  'ms-agrotrack-notify' = 8084;   'ms-agrotrack-audit' = 8086
  'ms-agrotrack-report' = 8085;   'ms-agrotrack-bff' = 8081
}
Write-Host "Issuer   : $issuer"
Write-Host "Audiences: api://$ClientId, $ClientId`n"
foreach ($s in $servicios.GetEnumerator()) {
  $jar = Get-ChildItem (Join-Path $root "$($s.Key)\target\*.jar") -Exclude *.original | Select-Object -First 1
  if (-not $jar) { throw "No hay jar para $($s.Key): ejecuta mvnw -DskipTests package" }
  $p = Start-Process -FilePath $java -ArgumentList @('-jar', "`"$($jar.FullName)`"", '--spring.profiles.active=local', "--server.port=$($s.Value)") `
      -WorkingDirectory (Join-Path $root $s.Key) -RedirectStandardOutput (Join-Path $logs "$($s.Key).log") -RedirectStandardError (Join-Path $logs "$($s.Key).err") -PassThru -WindowStyle Hidden
  Write-Host "  $($s.Key) pid $($p.Id) -> :$($s.Value)"
  if ($s.Key -like '*-admin') { Start-Sleep -Seconds 12 }
}

# Frontend en modo MSAL apuntando a este tenant
$envFile = Join-Path $root 'frontend-agrotrack\src\environments\environment.azure.ts'
@"
// Generado por infra/local/azure-local.ps1 — login real de Azure contra el backend local.
export const environment = {
  production: false,
  apiUrl: 'http://localhost:8081',
  auth: {
    mode: 'msal' as 'dev' | 'msal',
    msal: {
      clientId: '$ClientId',
      tenantId: '$TenantId',
      redirectUri: 'http://localhost:4200',
      apiScope: 'api://$ClientId/access_as_user',
    },
  },
};
"@ | Set-Content -Path $envFile -Encoding UTF8

Write-Host "`nServicios arrancando (60-90 s). Frontend con Azure:"
Write-Host "  cd $root\frontend-agrotrack"
Write-Host "  npm run start:azure          # http://localhost:4200 -> boton 'Iniciar sesion con Microsoft'"
Write-Host "`nLogs en $logs. Para detener: Get-Process java | Stop-Process"
