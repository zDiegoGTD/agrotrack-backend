<#
.SYNOPSIS
  Demuestra en vivo que el API Gateway y el BFF validan el JWT de Azure AD:
  rechazan los tokens invalidos y aceptan el correcto. Pensado para la demo
  (punto 4 de la pauta: "el API Manager valida JWT, rechaza invalidas y
  acepta correctas").

  Casos:
    1. Sin token                                    -> 401 (Gateway)
    2. Texto que no es un JWT                       -> 401 (Gateway)
    3. JWT con claims de Azure pero firma inventada -> 401 (Gateway)
    4. Token real de Azure                          -> 200
    5. Token real con el rol cambiado a mano        -> 401 (firma no calza)
    6. Token vencido (si se entrega -TokenVencido)  -> 401
    7. Token real directo a la EC2, sin Gateway     -> 403 ORIGEN_NO_PERMITIDO (BFF)
    8. Reporteria con un usuario que no es ADMIN    -> 403 (solo si el token no trae ADMIN)

  El token real se obtiene con el flujo de codigo de dispositivo (como
  smoke-aws.ps1) o se pasa con -Token.

.EXAMPLE
  .\probar-jwt.ps1
  .\probar-jwt.ps1 -Token "eyJ..." -TokenVencido "eyJ..."
#>
param(
  [string]$Token,
  [string]$TokenVencido,
  [string]$TenantId = '74c11418-e5f3-4253-9755-b665d755321c',
  [string]$ClientId = '44f417b1-ff99-4463-974a-56bb29cb66dd'
)
$ErrorActionPreference = 'Continue'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$hosts = Get-Content (Join-Path $root 'infra\.aws-hosts.json') -Raw | ConvertFrom-Json
$gateway = $hosts.gateway
$appsIp = $hosts.apps.publica
$fallos = 0

function B64Url([byte[]]$b) { [Convert]::ToBase64String($b).TrimEnd('=').Replace('+', '-').Replace('/', '_') }
function B64UrlTexto([string]$t) { B64Url ([Text.Encoding]::UTF8.GetBytes($t)) }
function DesdeB64Url([string]$t) {
  $t = $t.Replace('-', '+').Replace('_', '/'); $t += '=' * ((4 - $t.Length % 4) % 4)
  [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($t))
}

function Llamar([string]$url, [string]$tok) {
  $h = @{}
  if ($tok) { $h.Authorization = "Bearer $tok" }
  try {
    $r = Invoke-WebRequest $url -Headers $h -UseBasicParsing -TimeoutSec 30 -ErrorAction Stop
    return @{ status = [int]$r.StatusCode; codigo = '' }
  } catch {
    $resp = $_.Exception.Response
    if (-not $resp) { return @{ status = 0; codigo = $_.Exception.Message } }
    $cuerpo = ''
    try { $cuerpo = (New-Object IO.StreamReader($resp.GetResponseStream())).ReadToEnd() } catch { }
    $codigo = ''
    try { $codigo = ($cuerpo | ConvertFrom-Json).codigo } catch { }
    if (-not $codigo -and $cuerpo) { $codigo = $cuerpo.Substring(0, [Math]::Min(60, $cuerpo.Length)) }
    return @{ status = [int]$resp.StatusCode; codigo = $codigo }
  }
}

$resultados = @()
function Caso($n, $descripcion, $esperado, $r) {
  $ok = $r.status -eq $esperado
  if (-not $ok) { $script:fallos++ }
  $script:resultados += [pscustomobject]@{
    '#' = $n; Caso = $descripcion; Esperado = $esperado; Recibido = $r.status
    Detalle = $r.codigo; Resultado = $(if ($ok) { 'OK' } else { 'NO CALZA' })
  }
}

# ---------- token real ----------
if (-not $Token) {
  Write-Host "`n== Token real de Azure AD (codigo de dispositivo)" -ForegroundColor Cyan
  $dc = Invoke-RestMethod -Method Post -Uri "https://login.microsoftonline.com/$TenantId/oauth2/v2.0/devicecode" `
    -Body @{ client_id = $ClientId; scope = "api://$ClientId/access_as_user" }
  Write-Host "`n   $($dc.message)`n" -ForegroundColor Yellow
  Start-Process 'https://microsoft.com/devicelogin'
  Set-Clipboard -Value $dc.user_code -ErrorAction SilentlyContinue
  $limite = (Get-Date).AddSeconds([int]$dc.expires_in)
  while (-not $Token -and (Get-Date) -lt $limite) {
    Start-Sleep -Seconds ([int]$dc.interval)
    try {
      $Token = (Invoke-RestMethod -Method Post -Uri "https://login.microsoftonline.com/$TenantId/oauth2/v2.0/token" `
          -Body @{ grant_type = 'urn:ietf:params:oauth:grant-type:device_code'; client_id = $ClientId; device_code = $dc.device_code }).access_token
    } catch {
      $e = ($_.ErrorDetails.Message | ConvertFrom-Json -ErrorAction SilentlyContinue)
      if ($e.error -notin 'authorization_pending', 'slow_down') { Write-Host "Azure: $($e.error)" -ForegroundColor Red; exit 1 }
    }
  }
}
$partes = $Token.Split('.')
$claims = DesdeB64Url $partes[1] | ConvertFrom-Json
Write-Host "`n   Token de $($claims.name) <$($claims.preferred_username)>  roles: $($claims.roles -join ', ')  scp: $($claims.scp)"

# ---------- tokens invalidos ----------
$ahora = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$forjado = (B64UrlTexto '{"alg":"RS256","typ":"JWT","kid":"inventado"}') + '.' +
  (B64UrlTexto (@{ iss = "https://login.microsoftonline.com/$TenantId/v2.0"; aud = $ClientId; roles = @('ADMIN'); scp = 'access_as_user'
      oid = 'atacante'; name = 'Atacante'; iat = $ahora; nbf = $ahora; exp = $ahora + 3600 } | ConvertTo-Json -Compress)) + '.' +
  (B64UrlTexto 'firma-inventada-que-no-es-rsa')

$payloadAlterado = (DesdeB64Url $partes[1]) -replace '"roles":\[[^\]]*\]', '"roles":["ADMIN","AUDITOR","OPERADOR"]'
$alterado = $partes[0] + '.' + (B64UrlTexto $payloadAlterado) + '.' + $partes[2]

Write-Host "`n== Probando contra $gateway" -ForegroundColor Cyan
Caso 1 'Sin token' 401 (Llamar "$gateway/api/me" $null)
Caso 2 'Texto que no es un JWT' 401 (Llamar "$gateway/api/me" 'esto-no-es-un-jwt')
Caso 3 'Claims de Azure con firma inventada' 401 (Llamar "$gateway/api/me" $forjado)
Caso 4 'Token real de Azure' 200 (Llamar "$gateway/api/me" $Token)
Caso 5 'Token real con el rol cambiado a mano' 401 (Llamar "$gateway/api/me" $alterado)
if ($TokenVencido) { Caso 6 'Token vencido' 401 (Llamar "$gateway/api/me" $TokenVencido) }
Caso 7 'Token real directo a la EC2 (sin Gateway)' 403 (Llamar "http://${appsIp}:8081/api/me" $Token)
if ($claims.roles -notcontains 'ADMIN') {
  Caso 8 'Reporteria sin rol ADMIN' 403 (Llamar "$gateway/api/report/kpis?range=last24h" $Token)
}

$resultados | Format-Table -AutoSize -Wrap
if ($fallos -eq 0) { Write-Host 'Todas las respuestas calzan con lo esperado.' -ForegroundColor Green }
else { Write-Host "$fallos caso(s) no calzan." -ForegroundColor Red }
exit $fallos
