<#
.SYNOPSIS
  Reabre todo lo de la demo EP4: pestañas de Chrome (RabbitMQ, app, Azure, API Gateway),
  la presentación, el guion y una PowerShell en infra\aws con $g cargado.
  No enciende AWS: si el lab se cortó, corre antes .\encender.ps1.
#>
$ErrorActionPreference = 'Continue'
$hosts = Get-Content (Join-Path (Split-Path $PSScriptRoot -Parent) '.aws-hosts.json') | ConvertFrom-Json
$gw = 'https://85v8hc0ry6.execute-api.us-east-1.amazonaws.com'
$api = '44f417b1-ff99-4463-974a-56bb29cb66dd'

Start-Process chrome @(
  "http://$($hosts.mq.publica):15672",
  $gw,
  'https://entra.microsoft.com/#view/Microsoft_AAD_UsersAndTenants/UserManagementMenuBlade/~/AllUsers',
  "https://entra.microsoft.com/#view/Microsoft_AAD_RegisteredApps/ApplicationMenuBlade/~/AppRoles/appId/$api/isMSAApp~/false",
  "https://entra.microsoft.com/#view/Microsoft_AAD_RegisteredApps/ApplicationMenuBlade/~/ProtectAnAPI/appId/$api/isMSAApp~/false",
  'https://entra.microsoft.com/#view/Microsoft_AAD_IAM/StartboardApplicationsMenuBlade/~/AppAppsPreview/menuId~/null',
  'https://us-east-1.console.aws.amazon.com/apigateway/main/develop/routes?api=85v8hc0ry6&region=us-east-1'
)
$escritorio = [Environment]::GetFolderPath('Desktop')
Start-Process (Join-Path $escritorio 'AgroTrack-presentacion.pptx')
Start-Process (Join-Path $escritorio 'AgroTrack-guion-EP4.md')
Start-Process powershell -WorkingDirectory $PSScriptRoot -ArgumentList '-NoExit', '-Command', "`$g='$gw'; Write-Host 'Listo `$g. Copia el token desde la app y ejecuta:  `$t=(Read-Host ''token'').Trim()' -ForegroundColor Green"
