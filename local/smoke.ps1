<#
.SYNOPSIS
  Prueba de humo de punta a punta de AgroTrack en local.

  Requiere: infra/local/compose.yml levantado (Postgres, Rabbit, Kafka) y los
  jars empaquetados (mvnw -DskipTests package en cada servicio).

  Arranca los 8 servicios con perfil local, emite tokens con mint.mjs y
  recorre el flujo completo del enunciado:
    ADMIN crea producto y bodega -> CLIENTE registra entrega -> OPERADOR recibe,
    clasifica, pasa a despacho y despacha -> se verifica capacidad en catalog,
    ticket y guia PDF de notify, timeline en audit y KPIs en report.

.PARAMETER KeepRunning
  Deja los servicios corriendo al terminar (para usar el frontend).
#>
param([switch]$KeepRunning)

$ErrorActionPreference = 'Stop'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent   # .../AgroTrack
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
$logs = Join-Path $PSScriptRoot 'logs'; New-Item -ItemType Directory -Force $logs | Out-Null

$servicios = [ordered]@{
  'ms-agrotrack-mq-admin'    = 8087
  'ms-agrotrack-kafka-admin' = 8088
  'ms-agrotrack-catalog'     = 8083
  'ms-agrotrack-deliveries'  = 8082
  'ms-agrotrack-notify'      = 8084
  'ms-agrotrack-audit'       = 8086
  'ms-agrotrack-report'      = 8085
  'ms-agrotrack-bff'         = 8081
}
$procesos = @()
$fallos = 0

function Paso($texto) { Write-Host "`n== $texto" -ForegroundColor Cyan }
function Ok($texto)   { Write-Host "   OK   $texto" -ForegroundColor Green }
function Falla($texto){ Write-Host "   FALLA $texto" -ForegroundColor Red; $script:fallos++ }
function Verificar($cond, $texto) { if ($cond) { Ok $texto } else { Falla $texto } }

function Esperar-Salud($puerto, $nombre) {
  $limite = (Get-Date).AddSeconds(120)
  while ((Get-Date) -lt $limite) {
    try {
      $r = Invoke-RestMethod "http://localhost:$puerto/actuator/health" -TimeoutSec 3
      if ($r.status -eq 'UP') { return $true }
    } catch { }
    Start-Sleep -Seconds 2
  }
  return $false
}

function Api($metodo, $ruta, $token, $body = $null) {
  $h = @{ Authorization = "Bearer $token" }
  $p = @{ Method = $metodo; Uri = "http://localhost:8081$ruta"; Headers = $h; ContentType = 'application/json; charset=utf-8'; TimeoutSec = 30 }
  # Bytes UTF-8 explicitos: Invoke-RestMethod codifica los strings en Latin-1
  # por defecto y "EN_CLASIFICACIÓN" llegaria con la Ó rota.
  if ($null -ne $body) { $p.Body = [System.Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Compress)) }
  $r = Invoke-RestMethod @p
  # PowerShell 5.1 entrega un array JSON como UN solo objeto; se enumera para
  # que el @() de quien llama arme una lista plana y .Count sea real.
  if ($r -is [System.Collections.IEnumerable] -and -not ($r -is [string])) { foreach ($x in $r) { $x } } else { $r }
}

try {
  Paso 'Arrancando servicios (perfil local)'
  foreach ($s in $servicios.GetEnumerator()) {
    $jar = Get-ChildItem (Join-Path $root "$($s.Key)\target\*.jar") -Exclude *.original | Select-Object -First 1
    if (-not $jar) { throw "No hay jar para $($s.Key): ejecuta mvnw -DskipTests package" }
    $p = Start-Process -FilePath $java -ArgumentList @('-jar', "`"$($jar.FullName)`"", '--spring.profiles.active=local', "--server.port=$($s.Value)") `
        -WorkingDirectory (Join-Path $root $s.Key) -RedirectStandardOutput (Join-Path $logs "$($s.Key).log") -RedirectStandardError (Join-Path $logs "$($s.Key).err") -PassThru -WindowStyle Hidden
    $procesos += $p
    Write-Host "   $($s.Key) pid $($p.Id) puerto $($s.Value)"
    # Los administradores de topologia deben terminar antes de que arranquen los consumidores
    if ($s.Key -like '*-admin') { Verificar (Esperar-Salud $s.Value $s.Key) "$($s.Key) sano" }
  }
  foreach ($s in $servicios.GetEnumerator()) { if ($s.Key -notlike '*-admin') { Verificar (Esperar-Salud $s.Value $s.Key) "$($s.Key) sano" } }
  if ($fallos -gt 0) { throw 'Algun servicio no levanto; revisa infra/local/logs' }

  Paso 'Tokens'
  $mint = Join-Path $PSScriptRoot 'jwt\mint.mjs'
  $tAdmin = (& node $mint ADMIN admin-smoke).Trim()
  $tOper  = (& node $mint OPERADOR operador-smoke).Trim()
  $tCli   = (& node $mint CLIENTE productor-smoke).Trim()
  $tAud   = (& node $mint AUDITOR auditor-smoke).Trim()
  Verificar ($tAdmin.Length -gt 100) 'tokens emitidos'

  Paso '/api/me'
  $yo = Api GET '/api/me' $tOper
  Verificar ($yo.roles -contains 'OPERADOR') "me como OPERADOR ($($yo.userId))"

  Paso 'ADMIN crea producto y bodega'
  $stamp = Get-Date -Format 'HHmmss'
  $prod = Api POST '/api/catalog/productos' $tAdmin @{ codigo = "SMOKE-$stamp"; nombre = 'Trigo de prueba'; unidadMedida = 'KG'; tarifa = 120.5 }
  $bod  = Api POST '/api/catalog/bodegas' $tAdmin @{ nombre = "Bodega humo $stamp"; ubicacion = 'Talca'; capacidadTotal = 1000 }
  Verificar ($prod.id -gt 0 -and $bod.capacidadDisponible -eq 1000) "producto $($prod.id), bodega $($bod.id) con 1000"

  Paso 'CLIENTE registra una entrega de 300'
  $ent = Api POST '/api/deliveries' $tCli @{ productoId = $prod.id; bodegaId = $bod.id; cantidad = 300 }
  Verificar ($ent.estado -eq 'REGISTRADA' -and $ent.codigo -like 'DEL-*') "entrega $($ent.codigo) REGISTRADA"

  Paso 'CLIENTE intenta recibirla: debe ser 403'
  try { Api PUT "/api/deliveries/$($ent.id)/status" $tCli @{ status = 'RECIBIDA' }; Falla 'el productor pudo cambiar estado' }
  catch { Verificar ($_.Exception.Response.StatusCode.value__ -eq 403) '403 para el productor' }

  Paso 'OPERADOR intenta despachar sin recibir: debe ser 409'
  try { Api PUT "/api/deliveries/$($ent.id)/status" $tOper @{ status = 'EN_DESPACHO' }; Falla 'se salto la recepcion' }
  catch { Verificar ($_.Exception.Response.StatusCode.value__ -eq 409) '409 REGISTRADA -> EN_DESPACHO' }

  Paso 'OPERADOR recibe (peso 280) -> capacidad 720'
  $ent = Api PUT "/api/deliveries/$($ent.id)/status" $tOper @{ status = 'RECIBIDA'; pesoRecibido = 280 }
  $bod2 = Api GET "/api/catalog/bodegas/$($bod.id)" $tOper
  Verificar ($ent.estado -eq 'RECIBIDA' -and $bod2.capacidadDisponible -eq 720) "RECIBIDA; bodega con $($bod2.capacidadDisponible)"

  Paso 'OPERADOR: clasificacion (con tilde) -> despacho -> despachada'
  # La Ó se construye con su codigo Unicode: PowerShell 5.1 lee los .ps1 sin BOM
  # como ANSI y un literal con tilde llegaria corrupto.
  $conTilde = "EN_CLASIFICACI$([char]0x00D3)N"
  $ent = Api PUT "/api/deliveries/$($ent.id)/status" $tOper @{ status = $conTilde }
  $ent = Api PUT "/api/deliveries/$($ent.id)/status" $tOper @{ status = 'EN_DESPACHO' }
  $ent = Api PUT "/api/deliveries/$($ent.id)/status" $tOper @{ status = 'DESPACHADA' }
  Verificar ($ent.estado -eq 'DESPACHADA' -and $ent.terminal) 'DESPACHADA (terminal)'

  Paso 'notify: ticket de recepcion y guia PDF (RabbitMQ)'
  $salida = Join-Path $root 'ms-agrotrack-notify\salida'
  $limite = (Get-Date).AddSeconds(30)
  do { Start-Sleep 1; $ticket = Test-Path (Join-Path $salida "tickets\$($ent.codigo).txt"); $pdf = Test-Path (Join-Path $salida "vouchers\$($ent.codigo).pdf") } while (-not ($ticket -and $pdf) -and (Get-Date) -lt $limite)
  Verificar $ticket "ticket tickets\$($ent.codigo).txt"
  Verificar $pdf "guia vouchers\$($ent.codigo).pdf"

  Paso 'audit: timeline con los 5 eventos (Kafka)'
  $limite = (Get-Date).AddSeconds(30); $tl = @()
  do { Start-Sleep 1; $tl = @(Api GET "/api/audit/deliveries/$($ent.codigo)/timeline" $tAud) } while ($tl.Count -lt 5 -and (Get-Date) -lt $limite)
  Verificar ($tl.Count -eq 5) "timeline con $($tl.Count) eventos: $(($tl | ForEach-Object tipo) -join ' > ')"
  Verificar (($tl | Select-Object -First 1).tipo -eq 'delivery.registered' -and ($tl | Select-Object -Last 1).tipo -eq 'delivery.dispatched') 'en orden'

  Paso 'report: KPIs (Kafka)'
  $limite = (Get-Date).AddSeconds(30); $k = $null
  do { Start-Sleep 1; $k = Api GET '/api/report/kpis?range=last24h' $tAdmin } while ($k.entregasCerradas -lt 1 -and (Get-Date) -lt $limite)
  Verificar ($k.entregasCerradas -ge 1) "entregasCerradas=$($k.entregasCerradas), ciclo=$($k.tiempoCicloPromedioMin) min"
  # report es otro consumer group: puede ir unos ms detras de audit
  $limite = (Get-Date).AddSeconds(30); $top = @()
  do { Start-Sleep 1; $top = @(Api GET '/api/report/top-services?range=last7d' $tAdmin) } while ((@($top | Where-Object productoId -eq $prod.id)).Count -lt 1 -and (Get-Date) -lt $limite)
  Verificar ((@($top | Where-Object productoId -eq $prod.id)).Count -eq 1) "producto $($prod.id) en top-services"

  Paso 'Matriz de roles del BFF'
  try { Api GET '/api/report/kpis' $tCli; Falla 'CLIENTE vio reportes' } catch { Verificar ($_.Exception.Response.StatusCode.value__ -eq 403) 'CLIENTE 403 en /api/report' }
  try { Api GET '/api/audit/events' $tOper; Falla 'OPERADOR vio auditoria' } catch { Verificar ($_.Exception.Response.StatusCode.value__ -eq 403) 'OPERADOR 403 en /api/audit' }
  $topo = Api GET '/api/mq/topology' $tAdmin
  Verificar ($topo.totalEnDlq -eq 0) "RabbitMQ sin mensajes en DLQ ($($topo.flujos.Count) flujos)"
  $topics = @(Api GET '/api/kafka/topics' $tAdmin)
  Verificar (($topics | Where-Object existe).Count -eq 3) 'los 3 topicos de Kafka existen'
}
catch {
  Falla "excepcion: $($_.Exception.Message)"
}
finally {
  Write-Host ''
  if ($fallos -eq 0) { Write-Host "SMOKE OK - todo el flujo del enunciado funciona de punta a punta." -ForegroundColor Green }
  else { Write-Host "SMOKE FALLO - $fallos verificaciones fallaron. Logs en infra/local/logs" -ForegroundColor Red }
  if (-not $KeepRunning) {
    Write-Host 'Deteniendo servicios...'
    $procesos | ForEach-Object { try { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue } catch { } }
  } else {
    Write-Host "Servicios corriendo (pids: $(($procesos | ForEach-Object Id) -join ', ')). BFF en http://localhost:8081"
  }
}
exit $fallos
