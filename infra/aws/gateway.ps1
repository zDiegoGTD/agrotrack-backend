<#
.SYNOPSIS
  Crea (o actualiza) el API Gateway HTTP API con JWT Authorizer de Azure AD
  delante del BFF, tal como pide la seccion 4 del enunciado:
    issuer   https://login.microsoftonline.com/<TENANT_ID>/v2.0
    audience api://<CLIENT_ID> (y <CLIENT_ID>, forma v2)
  Lee la IP elastica de apps desde infra/.aws-hosts.json (lo deja crear.ps1).

.EXAMPLE
  .\gateway.ps1 -TenantId <TENANT_ID> -ClientId <CLIENT_ID>
#>
param(
  [Parameter(Mandatory)] [string]$TenantId,
  [Parameter(Mandatory)] [string]$ClientId,
  [string]$Region = 'us-east-1',
  [string]$Nombre = 'agrotrack-api'
)
$ErrorActionPreference = 'Stop'
$env:AWS_DEFAULT_REGION = $Region
$env:AWS_PAGER = ''
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
function Aws {
  # stderr no se convierte en excepcion: en PS 5.1 cualquier aviso de la CLI lo haria
  $ErrorActionPreference = 'Continue'
  $out = & aws.exe @args 2>&1
  if ($LASTEXITCODE -ne 0) { throw "aws $($args -join ' ') -> $($out | Out-String)" }
  return (($out | Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] }) | Out-String)
}

$hosts = Get-Content (Join-Path $root 'infra\.aws-hosts.json') -Raw | ConvertFrom-Json
$appsIp = $hosts.apps.publica
$issuer = "https://login.microsoftonline.com/$TenantId/v2.0"
$origen = "http://$appsIp"

Write-Host "== API Gateway '$Nombre' -> http://${appsIp}:8081" -ForegroundColor Cyan

# API (reutiliza si existe)
$apiId = (Aws apigatewayv2 get-apis --query "Items[?Name=='$Nombre'].ApiId | [0]" --output text).Trim()
if (-not $apiId -or $apiId -eq 'None') {
  $apiId = (Aws apigatewayv2 create-api --name $Nombre --protocol-type HTTP --query 'ApiId' --output text).Trim()
  Write-Host "   api creada $apiId"
} else { Write-Host "   api existe $apiId" }

# CORS: el navegador pregunta al Gateway, no al BFF
Aws apigatewayv2 update-api --api-id $apiId --cors-configuration "AllowOrigins=$origen,http://localhost:4200,AllowMethods=GET,POST,PUT,DELETE,OPTIONS,AllowHeaders=Authorization,Content-Type,Accept,MaxAge=3600" | Out-Null

# Secreto que el Gateway agrega a cada llamada al BFF (FiltroOrigenGateway):
# asi el BFF niega lo que llegue directo a la EC2 sin pasar por aqui.
$envAws = Join-Path $root 'infra\.env.aws'
$secreto = ((Get-Content $envAws) | Where-Object { $_ -like 'GATEWAY_SECRETO=*' }) -replace '^GATEWAY_SECRETO=', ''
if (-not $secreto) {
  $bytes = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
  $secreto = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
  Add-Content $envAws "GATEWAY_SECRETO=$secreto" -Encoding UTF8
  Write-Host '   GATEWAY_SECRETO generado en .env.aws (redesplegar apps para que el BFF lo conozca)'
}
# Por archivo: PowerShell 5.1 rompe las comillas de un JSON pasado como argumento
$paramsFile = Join-Path $env:TEMP 'agrotrack-gateway-params.json'
@{ 'append:header.X-Origen-Gateway' = $secreto } | ConvertTo-Json -Compress | Set-Content $paramsFile -Encoding ASCII

# Integracion HTTP proxy hacia el BFF. Se busca por su URI y no por posicion:
# gateway-frontend.ps1 agrega otras integraciones a la misma API.
$intId = (Aws apigatewayv2 get-integrations --api-id $apiId --query "Items[?contains(IntegrationUri, ':8081/')].IntegrationId | [0]" --output text).Trim()
# {proxy} captura solo lo que va DESPUES de /api/: hay que volver a poner el
# prefijo, o el BFF recibe /report/kpis, no calza con su matriz y responde 403.
$uri = "http://${appsIp}:8081/api/{proxy}"
if (-not $intId -or $intId -eq 'None') {
  $intId = (Aws apigatewayv2 create-integration --api-id $apiId --integration-type HTTP_PROXY --integration-method ANY --integration-uri $uri --payload-format-version 1.0 --request-parameters "file://$paramsFile" --query 'IntegrationId' --output text).Trim()
  Write-Host "   integracion creada $intId"
} else {
  Aws apigatewayv2 update-integration --api-id $apiId --integration-id $intId --integration-uri $uri --request-parameters "file://$paramsFile" | Out-Null
  Write-Host "   integracion actualizada $intId -> $uri (con cabecera X-Origen-Gateway)"
}
Remove-Item $paramsFile -ErrorAction SilentlyContinue

# Authorizer JWT de Azure AD
$authId = (Aws apigatewayv2 get-authorizers --api-id $apiId --query "Items[?Name=='azure-ad'].AuthorizerId | [0]" --output text).Trim()
$jwtCfg = "Audience=api://$ClientId,$ClientId,Issuer=$issuer"
if (-not $authId -or $authId -eq 'None') {
  $authId = (Aws apigatewayv2 create-authorizer --api-id $apiId --name azure-ad --authorizer-type JWT --identity-source '$request.header.Authorization' --jwt-configuration $jwtCfg --query 'AuthorizerId' --output text).Trim()
  Write-Host "   authorizer creado $authId"
} else {
  Aws apigatewayv2 update-authorizer --api-id $apiId --authorizer-id $authId --jwt-configuration $jwtCfg | Out-Null
  Write-Host "   authorizer actualizado $authId"
}

# Ruta ANY /api/{proxy+} protegida: token valido de Azure Y scope access_as_user.
# Sin el scope el Gateway responde 403 antes de tocar el BFF.
$routeId = (Aws apigatewayv2 get-routes --api-id $apiId --query "Items[?RouteKey=='ANY /api/{proxy+}'].RouteId | [0]" --output text).Trim()
if (-not $routeId -or $routeId -eq 'None') {
  Aws apigatewayv2 create-route --api-id $apiId --route-key 'ANY /api/{proxy+}' --target "integrations/$intId" --authorization-type JWT --authorizer-id $authId --authorization-scopes access_as_user | Out-Null
  Write-Host '   ruta creada ANY /api/{proxy+} (JWT + scope access_as_user)'
} else {
  Aws apigatewayv2 update-route --api-id $apiId --route-id $routeId --target "integrations/$intId" --authorization-type JWT --authorizer-id $authId --authorization-scopes access_as_user | Out-Null
  Write-Host '   ruta actualizada (JWT + scope access_as_user)'
}

# Preflight CORS sin authorizer: el navegador manda OPTIONS sin token y la
# ruta ANY con JWT lo rechazaria con 401, bloqueando todas las llamadas.
$optId = (Aws apigatewayv2 get-routes --api-id $apiId --query "Items[?RouteKey=='OPTIONS /api/{proxy+}'].RouteId | [0]" --output text).Trim()
if (-not $optId -or $optId -eq 'None') {
  Aws apigatewayv2 create-route --api-id $apiId --route-key 'OPTIONS /api/{proxy+}' --target "integrations/$intId" --authorization-type NONE | Out-Null
  Write-Host '   ruta OPTIONS /api/{proxy+} sin autorizacion (preflight CORS)'
}

# Stage $default con auto-deploy
$stage = (Aws apigatewayv2 get-stages --api-id $apiId --query "Items[?StageName=='`$default'].StageName | [0]" --output text).Trim()
if (-not $stage -or $stage -eq 'None') { Aws apigatewayv2 create-stage --api-id $apiId --stage-name '$default' --auto-deploy | Out-Null }

$invoke = (Aws apigatewayv2 get-api --api-id $apiId --query 'ApiEndpoint' --output text).Trim()
Write-Host "`n   Invoke URL: $invoke" -ForegroundColor Green

# Se guarda junto a las IPs para que smoke-aws.ps1 lo encuentre solo
$hostsFile = Join-Path $root 'infra\.aws-hosts.json'
$hosts | Add-Member -NotePropertyName gateway -NotePropertyValue $invoke -Force
$hosts | ConvertTo-Json -Depth 3 | Set-Content $hostsFile -Encoding UTF8

# Frontend de produccion apuntando al Gateway
$envProd = Join-Path $root 'frontend-agrotrack\src\environments\environment.prod.ts'
@"
/**
 * Produccion / AWS. Generado por infra/aws/gateway.ps1.
 * El scope es el del API (api://<CLIENT_ID>/access_as_user), el mismo audience
 * que validan el API Gateway y los servicios.
 */
export const environment = {
  production: true,
  apiUrl: '$invoke',
  auth: {
    mode: 'msal' as 'dev' | 'msal',
    msal: {
      clientId: '$ClientId',
      tenantId: '$TenantId',
      redirectUri: '$origen',
      apiScope: 'api://$ClientId/access_as_user',
    },
  },
};
"@ | Set-Content $envProd -Encoding UTF8
Write-Host "   environment.prod.ts actualizado (apiUrl=$invoke, redirectUri=$origen)"

Write-Host "`nPrueba sin token (esperado 401 desde el Gateway):"
try { Invoke-WebRequest "$invoke/api/me" -UseBasicParsing | Out-Null } catch { Write-Host "   $($_.Exception.Response.StatusCode.value__) $invoke/api/me" }

Write-Host "`nFalta:"
Write-Host "  1. En Azure -> AgroTrack -> Authentication -> SPA: agregar $origen como redirect URI"
Write-Host "  2. Volver a subir el frontend con la nueva config: .\subir.ps1 -Ip $appsIp -Stack apps"
Write-Host "  3. Abrir $origen e iniciar sesion con Microsoft"
