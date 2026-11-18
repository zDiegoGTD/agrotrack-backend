<#
.SYNOPSIS
  Prueba en vivo la API de administracion de Kafka (ms-agrotrack-kafka-admin)
  contra el despliegue en AWS, pasando por el API Gateway con un token real.

  Crea un topico de prueba con su configuracion, le cambia la retencion, le
  aumenta las particiones, lista los consumer groups con su lag y despues lo
  elimina; tambien comprueba las protecciones: topicos del sistema, reducir
  particiones, datos invalidos, mover offsets de un grupo activo, un topico
  inexistente y la llamada sin token. Al terminar no queda nada creado.

  Pensado para la demo del EP5. Con Kafka UI abierta se ve aparecer
  demo.kafka con 3 particiones y despues 4.

.EXAMPLE
  .\probar-kafka-admin.ps1
  .\probar-kafka-admin.ps1 -Token "eyJ..."      # token de un usuario ADMIN
  .\probar-kafka-admin.ps1 -Pausa               # se detiene tras crear, para mirarlo en Kafka UI
  .\probar-kafka-admin.ps1 -Base http://localhost:8088 -Token (node ..\local\jwt\mint.mjs ADMIN)   # local, sin Gateway
#>
param(
  [string]$Token,
  [switch]$Pausa,
  # Por defecto el API Gateway de AWS; en local, la URL del servicio o del BFF
  [string]$Base,
  [string]$TenantId = '74c11418-e5f3-4253-9755-b665d755321c',
  # App de la API: su scope es el que valida el Gateway
  [string]$ClientId = '44f417b1-ff99-4463-974a-56bb29cb66dd',
  # App del frontend (SPA) que inicia sesion con el codigo de dispositivo
  [string]$SpaClientId = '3406ecc1-064d-4ef5-ac27-ad91afa6f00c'
)
$ErrorActionPreference = 'Continue'
if (-not $Base) {
  $root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
  $hosts = Get-Content (Join-Path $root 'infra\.aws-hosts.json') -Raw | ConvertFrom-Json
  $Base = $hosts.gateway
}
$api = "$($Base.TrimEnd('/'))/api/kafka"
$fallos = 0

function Llamar([string]$metodo, [string]$url, [string]$tok, $cuerpo = $null) {
  $h = @{}
  if ($tok) { $h.Authorization = "Bearer $tok" }
  $p = @{ Method = $metodo; Uri = $url; Headers = $h; UseBasicParsing = $true; TimeoutSec = 30; ErrorAction = 'Stop' }
  if ($null -ne $cuerpo) { $p.Body = ($cuerpo | ConvertTo-Json -Compress -Depth 5); $p.ContentType = 'application/json' }
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

Write-Host "`n== Administrando Kafka por $api`n" -ForegroundColor Cyan

$topico = 'demo.kafka'

# ---------- ciclo completo ----------
Caso 1 'Listar topicos' 200 (Llamar GET "$api/topics" $Token)
Caso 2 "Crear $topico (3 particiones, retencion 1 h)" 201 (Llamar POST "$api/topics" $Token @{
    nombre = $topico; particiones = 3; replicas = 1; configuracion = @{ 'retention.ms' = '3600000' } })
Caso 3 'Cambiar su retencion a 2 h' 200 (Llamar PATCH "$api/topics/$topico/config" $Token @{ configuracion = @{ 'retention.ms' = '7200000' } })
Caso 4 'Aumentar a 4 particiones' 200 (Llamar POST "$api/topics/$topico/particiones" $Token @{ particiones = 4 })
$r = Llamar GET "$api/topics/$topico" $Token
$t = try { $r.cuerpo | ConvertFrom-Json } catch { $null }
$cambiado = $t -and $t.particiones -eq 4 -and $t.configuracion.'retention.ms' -eq '7200000'
Caso 5 'Kafka reporta 4 particiones y 2 h' 200 @{ status = $(if ($cambiado) { 200 } else { 500 }); codigo = '' }

if ($Pausa) {
  Write-Host "   Creado: mira Kafka UI (Topics -> $topico). Enter para continuar..." -ForegroundColor Yellow
  [void](Read-Host)
}

$r = Llamar GET "$api/consumer-groups" $Token
Caso 6 'Consumer groups con su lag' 200 $r
if ($r.status -eq 200) {
  ($r.cuerpo | ConvertFrom-Json) | Format-Table grupo, estado, miembros, lagTotal -AutoSize | Out-Host
}
Caso 7 'Detalle de audit-consumer' 200 (Llamar GET "$api/consumer-groups/audit-consumer" $Token)
Caso 8 'Bajar particiones (4 -> 2)' 400 (Llamar POST "$api/topics/$topico/particiones" $Token @{ particiones = 2 }) 'DATOS_INVALIDOS'
Caso 9 "Eliminar $topico" 204 (Llamar DELETE "$api/topics/$topico" $Token)

# ---------- protecciones ----------
Caso 10 'Borrar un topico del sistema (deliveries.events)' 403 (Llamar DELETE "$api/topics/deliveries.events" $Token) 'RECURSO_DEL_SISTEMA'
Caso 11 'Compactar un topico del sistema' 403 (Llamar PATCH "$api/topics/deliveries.events/config" $Token @{ configuracion = @{ 'cleanup.policy' = 'compact' } }) 'RECURSO_DEL_SISTEMA'
Caso 12 'Mover offsets de un grupo activo' 409 (Llamar POST "$api/consumer-groups/audit-consumer/topics/deliveries.events/offsets" $Token @{ posicion = 'INICIO' }) 'CONFLICTO_CON_EXISTENTE'
Caso 13 'Topico con nombre interno (__x)' 400 (Llamar POST "$api/topics" $Token @{ nombre = '__x'; particiones = 1; replicas = 1 }) 'DATOS_INVALIDOS'
Caso 14 'Borrar un topico que no existe' 404 (Llamar DELETE "$api/topics/no.existe" $Token) 'NO_EXISTE'
Caso 15 'Crear topico sin token' 401 (Llamar POST "$api/topics" '' @{ nombre = 'x'; particiones = 1; replicas = 1 })

$resultados | Format-Table -AutoSize
if ($fallos) { Write-Host "$fallos caso(s) no calzan con lo esperado." -ForegroundColor Red; exit 1 }
Write-Host 'Todas las respuestas calzan con lo esperado.' -ForegroundColor Green
