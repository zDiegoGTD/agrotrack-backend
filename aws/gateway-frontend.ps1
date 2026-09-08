<#
.SYNOPSIS
  Añade al API Gateway la ruta que sirve el frontend, para que la aplicación
  quede accesible por HTTPS.

  Por qué: Azure AD **exige HTTPS** en los redirect URI de aplicaciones SPA
  (solo hace excepción con localhost). El frontend en la EC2 se sirve por
  HTTP simple, así que `http://<ip>` no se puede registrar y el login no
  puede volver a la app. El API Gateway ya expone HTTPS con certificado
  válido, así que se usa también como puerta del frontend.

  Efecto secundario bueno: frontend y API quedan en el **mismo origen**, así
  que desaparece el CORS. Y refuerza lo que pide la pauta, que todo el
  consumo pase por el API Manager.

  Rutas resultantes:
    ANY  /api/{proxy+}   -> BFF        (con JWT Authorizer)
    ANY  /{proxy+}       -> frontend   (sin autorización: es la SPA)
    GET  /               -> frontend

.EXAMPLE
  .\gateway-frontend.ps1
#>
param(
  [string]$Region = 'us-east-1',
  [string]$Nombre = 'agrotrack-api'
)
$ErrorActionPreference = 'Continue'
$env:AWS_DEFAULT_REGION = $Region
$env:AWS_PAGER = ''
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
function Aws {
  $ErrorActionPreference = 'Continue'
  $out = & aws.exe @args 2>&1
  if ($LASTEXITCODE -ne 0) { throw "aws $($args -join ' ') -> $($out | Out-String)" }
  return (($out | Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] }) | Out-String)
}

$hosts = Get-Content (Join-Path $root 'infra\.aws-hosts.json') -Raw | ConvertFrom-Json
$appsIp = $hosts.apps.publica
$apiId = (Aws apigatewayv2 get-apis --query "Items[?Name=='$Nombre'].ApiId | [0]" --output text).Trim()
if (-not $apiId -or $apiId -eq 'None') { throw "No existe la API '$Nombre'. Ejecuta gateway.ps1 primero." }

Write-Host "== API $apiId -> frontend en http://${appsIp}:80" -ForegroundColor Cyan

# Integración hacia el frontend (nginx en el puerto 80)
$uriFront = "http://${appsIp}/{proxy}"
$intFront = (Aws apigatewayv2 get-integrations --api-id $apiId `
    --query "Items[?IntegrationUri=='$uriFront'].IntegrationId | [0]" --output text).Trim()
if (-not $intFront -or $intFront -eq 'None') {
  $intFront = (Aws apigatewayv2 create-integration --api-id $apiId --integration-type HTTP_PROXY `
      --integration-method ANY --integration-uri $uriFront --payload-format-version 1.0 `
      --query 'IntegrationId' --output text).Trim()
  Write-Host "   integracion frontend creada $intFront"
} else { Write-Host "   integracion frontend existe $intFront" }

# La raiz necesita su propia integracion: {proxy} vacio no resuelve
$uriRaiz = "http://${appsIp}/"
$intRaiz = (Aws apigatewayv2 get-integrations --api-id $apiId `
    --query "Items[?IntegrationUri=='$uriRaiz'].IntegrationId | [0]" --output text).Trim()
if (-not $intRaiz -or $intRaiz -eq 'None') {
  $intRaiz = (Aws apigatewayv2 create-integration --api-id $apiId --integration-type HTTP_PROXY `
      --integration-method ANY --integration-uri $uriRaiz --payload-format-version 1.0 `
      --query 'IntegrationId' --output text).Trim()
  Write-Host "   integracion raiz creada $intRaiz"
} else { Write-Host "   integracion raiz existe $intRaiz" }

function Ruta($clave, $integracion) {
  $id = (Aws apigatewayv2 get-routes --api-id $apiId --query "Items[?RouteKey=='$clave'].RouteId | [0]" --output text).Trim()
  if (-not $id -or $id -eq 'None') {
    Aws apigatewayv2 create-route --api-id $apiId --route-key $clave --target "integrations/$integracion" --authorization-type NONE | Out-Null
    Write-Host "   ruta creada  $clave"
  } else {
    Aws apigatewayv2 update-route --api-id $apiId --route-id $id --target "integrations/$integracion" --authorization-type NONE | Out-Null
    Write-Host "   ruta actualizada $clave"
  }
}
# La SPA es publica: sin token no hay forma de mostrar la pantalla de login.
# Lo protegido es /api/{proxy+}, que conserva su JWT Authorizer.
Ruta 'ANY /{proxy+}' $intFront
Ruta 'GET /' $intRaiz

$invoke = (Aws apigatewayv2 get-api --api-id $apiId --query 'ApiEndpoint' --output text).Trim()

# Mismo origen para frontend y API: ya no hace falta CORS, pero se deja el
# origen HTTPS por si se sirve el frontend tambien desde la EC2.
Aws apigatewayv2 update-api --api-id $apiId --cors-configuration "AllowOrigins=$invoke,http://localhost:4200,AllowMethods=GET,POST,PUT,DELETE,OPTIONS,AllowHeaders=Authorization,Content-Type,Accept,MaxAge=3600" | Out-Null

# El frontend se reconfigura: misma URL para la app y para el API
$envProd = Join-Path $root 'frontend-agrotrack\src\environments\environment.prod.ts'
$clientId = (Select-String -Path $envProd -Pattern "clientId: '([^']+)'").Matches[0].Groups[1].Value
$tenantId = (Select-String -Path $envProd -Pattern "tenantId: '([^']+)'").Matches[0].Groups[1].Value
@"
/**
 * Produccion / AWS. Generado por infra/aws/gateway-frontend.ps1.
 *
 * La aplicacion y el API viven en el MISMO origen: el API Gateway sirve la
 * SPA en / y el API en /api. Eso da HTTPS (que Azure exige para los redirect
 * URI de una SPA) y elimina el CORS.
 */
export const environment = {
  production: true,
  apiUrl: '$invoke',
  auth: {
    mode: 'msal' as 'dev' | 'msal',
    msal: {
      clientId: '$clientId',
      tenantId: '$tenantId',
      redirectUri: '$invoke',
      apiScope: 'api://$clientId/access_as_user',
    },
  },
};
"@ | Set-Content $envProd -Encoding UTF8

Write-Host "`n   Aplicacion: $invoke" -ForegroundColor Green
Write-Host "   environment.prod.ts actualizado (apiUrl y redirectUri = $invoke)"
Write-Host "`nFalta:"
Write-Host "  1. En Azure -> AgroTrack -> Authentication -> SPA, agregar:  $invoke"
Write-Host "  2. Redesplegar el frontend:  .\subir.ps1 -Ip $appsIp -Stack apps"
