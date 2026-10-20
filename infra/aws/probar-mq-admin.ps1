<#
.SYNOPSIS
  Prueba en vivo la API de administracion de RabbitMQ (ms-agrotrack-mq-admin)
  contra el despliegue en AWS, pasando por el API Gateway con un token real.

  Crea una cola (con su DLQ), un exchange topic y un binding de prueba, los
  consulta y los elimina; despues comprueba las protecciones: borrar una cola
  del sistema, datos invalidos, un recurso inexistente y la llamada sin token.
  Al terminar no queda nada creado.

  Pensado para la demo del EP3/EP4 (indicadores del administrador de RabbitMQ).
  Con la Management UI abierta se ve aparecer y desaparecer q.demo.admin.

.EXAMPLE
  .\probar-mq-admin.ps1
  .\probar-mq-admin.ps1 -Token "eyJ..."      # token de un usuario ADMIN
  .\probar-mq-admin.ps1 -Pausa               # se detiene tras crear, para mirarlo en la UI
#>
param(
  [string]$Token,
  [switch]$Pausa,
  [string]$TenantId = '74c11418-e5f3-4253-9755-b665d755321c',
  # App de la API: su scope es el que valida el Gateway
  [string]$ClientId = '44f417b1-ff99-4463-974a-56bb29cb66dd',
  # App del frontend (SPA) que inicia sesion con el codigo de dispositivo
  [string]$SpaClientId = '3406ecc1-064d-4ef5-ac27-ad91afa6f00c'
)
$ErrorActionPreference = 'Continue'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$hosts = Get-Content (Join-Path $root 'infra\.aws-hosts.json') -Raw | ConvertFrom-Json
$api = "$($hosts.gateway)/api/mq"
$fallos = 0

function Llamar([string]$metodo, [string]$url, [string]$tok, $cuerpo = $null) {
  $h = @{}
  if ($tok) { $h.Authorization = "Bearer $tok" }
  $p = @{ Method = $metodo; Uri = $url; Headers = $h; UseBasicParsing = $true; TimeoutSec = 30; ErrorAction = 'Stop' }
  if ($null -ne $cuerpo) { $p.Body = ($cuerpo | ConvertTo-Json -Compress); $p.ContentType = 'application/json' }
  try {
    $r = Invoke-WebRequest @p
    return @{ status = [int]$r.StatusCode; codigo = ''; cuerpo = $r.Content }
  } catch {
    $resp = $_.Exception.Response
    if (-not $resp) { return @{ status = 0; codigo = $_.Exception.Message; cuerpo = '' } }
    # En Windows PowerShell 5.1 el cuerpo del error llega en ErrorDetails; el stream ya viene consumido.
    $texto = "$($_.ErrorDetails.Message)"
    if (-not $texto) { try { $texto = (New-Object IO.StreamReader($resp.GetResponseStream())).ReadToEnd() } catch { } }
    $codigo = ''
    try { $codigo = ($texto | ConvertFrom-Json).codigo } catch { }
    if (-not $codigo -and $texto) { $codigo = $texto.Substring(0, [Math]::Min(60, $texto.Length)) }
    return @{ status = [int]$resp.StatusCode; codigo = $codigo; cuerpo = $texto }
  }
}

$resultados = @()
function Caso($n, $descripcion, $esperado, $r, [string]$codigoEsperado = '') {
  $ok = $r.status -eq $esperado -and (-not $codigoEsperado -or $r.codigo -eq $codigoEsperado)
  if (-not $ok) { $script:fallos++ }
  $script:resultados += [pscustomobject]@{
    '#' = $n; Caso = $descripcion; Esperado = $esperado; Recibido = $r.status
    Detalle = $r.codigo; Resultado = $(if ($ok) { 'OK' } else { 'NO CALZA' })
  }
}

# ---------- token real de un ADMIN ----------
if (-not $Token) {
  Write-Host "`n== Token de Azure AD de un usuario ADMIN (codigo de dispositivo)" -ForegroundColor Cyan
  $dc = Invoke-RestMethod -Method Post -Uri "https://login.microsoftonline.com/$TenantId/oauth2/v2.0/devicecode" `
    -Body @{ client_id = $SpaClientId; scope = "api://$ClientId/access_as_user" }
  Write-Host "`n   $($dc.message)`n" -ForegroundColor Yellow
  Start-Process 'https://microsoft.com/devicelogin'
  Set-Clipboard -Value $dc.user_code -ErrorAction SilentlyContinue
  $limite = (Get-Date).AddSeconds([int]$dc.expires_in)
  while (-not $Token -and (Get-Date) -lt $limite) {
    Start-Sleep -Seconds ([int]$dc.interval)
    try {
      $Token = (Invoke-RestMethod -Method Post -Uri "https://login.microsoftonline.com/$TenantId/oauth2/v2.0/token" `
          -Body @{ grant_type = 'urn:ietf:params:oauth:grant-type:device_code'; client_id = $SpaClientId; device_code = $dc.device_code }).access_token
    } catch {
      $e = ($_.ErrorDetails.Message | ConvertFrom-Json -ErrorAction SilentlyContinue)
      if ($e.error -notin 'authorization_pending', 'slow_down') { Write-Host "Azure: $($e.error)" -ForegroundColor Red; exit 1 }
    }
  }
}

Write-Host "`n== Administrando RabbitMQ por $api`n" -ForegroundColor Cyan

$cola = 'q.demo.admin'; $exchange = 'ex.demo.admin'
$binding = @{ cola = $cola; exchange = $exchange; routingKey = 'demo.#' }

# ---------- ciclo completo ----------
Caso 1 'Topologia del sistema' 200 (Llamar GET "$api/topology" $Token)
Caso 2 "Crear cola $cola (con DLQ)" 201 (Llamar POST "$api/queues" $Token @{ nombre = $cola })
$r = Llamar GET "$api/queues/$cola.dlq" $Token
$existeDlq = try { ($r.cuerpo | ConvertFrom-Json).existe } catch { $false }
Caso 3 "Su DLQ $cola.dlq existe" 200 @{ status = $(if ($existeDlq) { 200 } else { 404 }); codigo = '' }
Caso 4 "Crear exchange topic $exchange" 201 (Llamar POST "$api/exchanges" $Token @{ nombre = $exchange; tipo = 'TOPIC' })
Caso 5 'Crear binding demo.#' 201 (Llamar POST "$api/bindings" $Token $binding)

if ($Pausa) {
  Write-Host "   Creado: mira la Management UI (Queues y Exchanges). Enter para continuar..." -ForegroundColor Yellow
  [void](Read-Host)
}

Caso 6 'Eliminar el binding' 204 (Llamar DELETE "$api/bindings" $Token $binding)
Caso 7 'Eliminar el exchange' 204 (Llamar DELETE "$api/exchanges/$exchange" $Token)
Caso 8 'Eliminar la cola' 204 (Llamar DELETE "$api/queues/$cola" $Token)
Caso 9 'Eliminar su DLQ' 204 (Llamar DELETE "$api/queues/$cola.dlq" $Token)

# ---------- protecciones ----------
Caso 10 'Borrar una cola del sistema (q.cmd.email)' 403 (Llamar DELETE "$api/queues/q.cmd.email" $Token) 'RECURSO_DEL_SISTEMA'
Caso 11 'Cola con nombre vacio' 400 (Llamar POST "$api/queues" $Token @{ nombre = '' }) 'DATOS_INVALIDOS'
Caso 12 'Exchange de tipo no admitido (HEADERS)' 400 (Llamar POST "$api/exchanges" $Token @{ nombre = 'ex.x'; tipo = 'HEADERS' }) 'DATOS_INVALIDOS'
Caso 13 'Borrar una cola que no existe' 404 (Llamar DELETE "$api/queues/q.no.existe" $Token) 'NO_EXISTE'
Caso 14 'Crear cola sin token' 401 (Llamar POST "$api/queues" '' @{ nombre = 'q.x' })

$resultados | Format-Table -AutoSize
if ($fallos) { Write-Host "$fallos caso(s) no calzan con lo esperado." -ForegroundColor Red; exit 1 }
Write-Host 'Todas las respuestas calzan con lo esperado.' -ForegroundColor Green
