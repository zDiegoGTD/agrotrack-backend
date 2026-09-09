<#
.SYNOPSIS
  Prueba de humo de punta a punta contra el despliegue en AWS, con un token
  REAL de Azure AD y pasando por el API Gateway.

  Obtiene el token con el flujo de codigo de dispositivo: no necesita secreto
  ni navegador embebido. Muestra un codigo, lo pegas en microsoft.com/devicelogin
  con la cuenta que tenga los roles, y el script sigue solo.

  Requisito en Azure: AgroTrack -> Authentication -> "Allow public client flows" = Si.

.EXAMPLE
  .\smoke-aws.ps1
  .\smoke-aws.ps1 -Token "eyJ..."      # si ya tienes uno (de las devtools del navegador)
#>
param(
  [string]$Token,
  [string]$TenantId = '74c11418-e5f3-4253-9755-b665d755321c',
  [string]$ClientId = '44f417b1-ff99-4463-974a-56bb29cb66dd',
  [string]$Gateway
)
$ErrorActionPreference = 'Continue'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$fallos = 0

function Paso($t) { Write-Host "`n== $t" -ForegroundColor Cyan }
function Ok($t) { Write-Host "   OK   $t" -ForegroundColor Green }
function Falla($t) { Write-Host "   FALLA $t" -ForegroundColor Red; $script:fallos++ }
function Verificar($cond, $t) { if ($cond) { Ok $t } else { Falla $t } }

if (-not $Gateway) {
  $hosts = Get-Content (Join-Path $root 'infra\.aws-hosts.json') -Raw | ConvertFrom-Json
  $Gateway = $hosts.gateway
  if (-not $Gateway) { $Gateway = 'https://85v8hc0ry6.execute-api.us-east-1.amazonaws.com' }
}

# ---------- token ----------
if (-not $Token) {
  Paso 'Autenticacion con Azure AD (codigo de dispositivo)'
  $scope = "api://$ClientId/access_as_user"
  $dc = Invoke-RestMethod -Method Post -Uri "https://login.microsoftonline.com/$TenantId/oauth2/v2.0/devicecode" `
    -Body @{ client_id = $ClientId; scope = "$scope offline_access" }

  Write-Host "`n   $($dc.message)`n" -ForegroundColor Yellow
  Start-Process 'https://microsoft.com/devicelogin'
  Set-Clipboard -Value $dc.user_code -ErrorAction SilentlyContinue
  Write-Host "   (el codigo $($dc.user_code) quedo en el portapapeles)"

  $limite = (Get-Date).AddSeconds([int]$dc.expires_in)
  while (-not $Token -and (Get-Date) -lt $limite) {
    Start-Sleep -Seconds ([int]$dc.interval)
    try {
      $r = Invoke-RestMethod -Method Post -Uri "https://login.microsoftonline.com/$TenantId/oauth2/v2.0/token" `
        -Body @{ grant_type = 'urn:ietf:params:oauth:grant-type:device_code'; client_id = $ClientId; device_code = $dc.device_code }
      $Token = $r.access_token
    } catch {
      $e = ($_.ErrorDetails.Message | ConvertFrom-Json -ErrorAction SilentlyContinue)
      if ($e.error -notin 'authorization_pending', 'slow_down') { Falla "Azure: $($e.error) - $($e.error_description)"; exit 1 }
    }
  }
  if (-not $Token) { Falla 'no se completo el login'; exit 1 }
}

$claims = ($Token.Split('.')[1]); $claims += '=' * ((4 - $claims.Length % 4) % 4)
$c = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($claims.Replace('-', '+').Replace('_', '/'))) | ConvertFrom-Json
Ok "token de $($c.name) <$($c.preferred_username)>"
Verificar ($c.iss -like "*/$TenantId/v2.0") "issuer v2 correcto ($($c.iss))"
Verificar ($c.roles) "roles en el token: $($c.roles -join ', ')"
$roles = @($c.roles)

function Api($metodo, $ruta, $body = $null) {
  $p = @{ Method = $metodo; Uri = "$Gateway$ruta"; Headers = @{ Authorization = "Bearer $Token" }
          ContentType = 'application/json; charset=utf-8'; TimeoutSec = 60 }
  if ($null -ne $body) { $p.Body = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Compress)) }
  $r = Invoke-RestMethod @p
  if ($r -is [Collections.IEnumerable] -and -not ($r -is [string])) { foreach ($x in $r) { $x } } else { $r }
}

Paso "API Gateway: $Gateway"
try { Invoke-WebRequest "$Gateway/api/me" -UseBasicParsing -TimeoutSec 20 | Out-Null; Falla 'sin token deberia dar 401' }
catch { Verificar ($_.Exception.Response.StatusCode.value__ -eq 401) 'sin token -> 401 (lo rechaza el Gateway, no llega al BFF)' }

$yo = Api GET '/api/me'
Verificar ($yo.roles.Count -gt 0) "/api/me -> $($yo.nombre), roles $($yo.roles -join '+')"

if ($roles -notcontains 'ADMIN' -or $roles -notcontains 'OPERADOR' -or $roles -notcontains 'CLIENTE') {
  Write-Host "`n   Con este usuario solo se puede probar parcialmente." -ForegroundColor Yellow
  Write-Host '   Para el flujo completo, asignate ADMIN, OPERADOR y CLIENTE en'
  Write-Host '   Entra ID -> Aplicaciones empresariales -> AgroTrack -> Usuarios y grupos.'
}

if ($roles -contains 'ADMIN') {
  Paso 'ADMIN: catalogo'
  $stamp = Get-Date -Format 'HHmmss'
  $prod = Api POST '/api/catalog/productos' @{ codigo = "AWS-$stamp"; nombre = 'Trigo AWS'; unidadMedida = 'KG'; tarifa = 150 }
  $bod = Api POST '/api/catalog/bodegas' @{ nombre = "Bodega AWS $stamp"; ubicacion = 'us-east-1'; capacidadTotal = 1000 }
  Verificar ($prod.id -gt 0 -and $bod.capacidadDisponible -eq 1000) "producto $($prod.id), bodega $($bod.id) con 1000"
}

if ($roles -contains 'CLIENTE' -or $roles -contains 'OPERADOR' -or $roles -contains 'ADMIN') {
  Paso 'Registrar y mover una entrega'
  $ent = Api POST '/api/deliveries' @{ productoId = $prod.id; bodegaId = $bod.id; cantidad = 300 }
  Verificar ($ent.estado -eq 'REGISTRADA') "entrega $($ent.codigo) REGISTRADA"

  try { Api PUT "/api/deliveries/$($ent.id)/status" @{ status = 'EN_DESPACHO' } | Out-Null; Falla 'se salto la recepcion' }
  catch { Verificar ($_.Exception.Response.StatusCode.value__ -eq 409) '409 al saltarse la recepcion' }

  $ent = Api PUT "/api/deliveries/$($ent.id)/status" @{ status = 'RECIBIDA'; pesoRecibido = 280 }
  $bod2 = Api GET "/api/catalog/bodegas/$($bod.id)"
  Verificar ($bod2.capacidadDisponible -eq 720) "RECIBIDA; bodega $($bod2.capacidadDisponible) (bajo 280)"

  $conTilde = "EN_CLASIFICACI$([char]0x00D3)N"
  $ent = Api PUT "/api/deliveries/$($ent.id)/status" @{ status = $conTilde }
  $ent = Api PUT "/api/deliveries/$($ent.id)/status" @{ status = 'EN_DESPACHO' }
  $ent = Api PUT "/api/deliveries/$($ent.id)/status" @{ status = 'DESPACHADA' }
  Verificar ($ent.estado -eq 'DESPACHADA') 'DESPACHADA (paso por los 5 estados)'
}

if ($roles -contains 'ADMIN' -or $roles -contains 'AUDITOR') {
  Paso 'Auditoria (Kafka -> audit)'
  $limite = (Get-Date).AddSeconds(60); $tl = @()
  do { Start-Sleep 2; $tl = @(Api GET "/api/audit/deliveries/$($ent.codigo)/timeline") } while ($tl.Count -lt 5 -and (Get-Date) -lt $limite)
  Verificar ($tl.Count -eq 5) "timeline con $($tl.Count) eventos: $(($tl | ForEach-Object tipo) -join ' > ')"
}

if ($roles -contains 'ADMIN') {
  Paso 'Reporteria (Kafka -> report)'
  $limite = (Get-Date).AddSeconds(60); $k = $null
  do { Start-Sleep 2; $k = Api GET '/api/report/kpis?range=last24h' } while ($k.entregasCerradas -lt 1 -and (Get-Date) -lt $limite)
  Verificar ($k.entregasCerradas -ge 1) "entregasCerradas=$($k.entregasCerradas), ciclo=$($k.tiempoCicloPromedioMin) min"

  Paso 'Mensajeria'
  $topo = Api GET '/api/mq/topology'
  Verificar ($topo.flujos.Count -eq 3 -and $topo.totalEnDlq -eq 0) "RabbitMQ: 3 flujos, $($topo.totalEnDlq) en DLQ"
  $topics = @(Api GET '/api/kafka/topics')
  Verificar ((@($topics | Where-Object existe)).Count -eq 3) "Kafka: $((@($topics | Where-Object { $_.existe })).Count)/3 topicos, replicas $($topics[0].replicas)"
}

Write-Host ''
if ($fallos -eq 0) { Write-Host 'SMOKE AWS OK - el flujo del enunciado funciona en la nube, con identidad real de Azure.' -ForegroundColor Green }
else { Write-Host "SMOKE AWS: $fallos verificaciones fallaron." -ForegroundColor Red }
exit $fallos
