# Checklist de entorno

Estado al 7 de septiembre de 2026.

| Componente | Estado | Acción |
|---|---|---|
| JDK 21 | ✅ Instalado | `C:\Users\deint\tools\jdk-21.0.12.1+1`, `JAVA_HOME` y `PATH` de usuario ya apuntan ahí |
| Node 24 / npm | ✅ Ya estaba | — |
| Git 2.55 | ✅ Ya estaba | — |
| Angular 22 + MSAL | ✅ Instalado | `frontend-agrotrack` con `@azure/msal-angular` 6 |
| 6 servicios Spring | ✅ Generados y compilando | Boot 3.5.16, Java 21, Maven |
| **WSL2** | ❌ **Roto** | Requiere admin — ver abajo |
| **Docker Desktop** | ❌ **No instalado** | Requiere admin — ver abajo |
| **Azure App Registration** | ⚠️ Sin verificar | Sólo lo puedes comprobar tú — ver abajo |

---

## 1. Reparar WSL2 (PowerShell **como administrador**)

Diagnóstico: el paquete de WSL está instalado (v2.7.3.0) pero la
característica de Windows que lo habilita no lo está — por eso el servicio
`LxssManager` no existe y `wsl` responde `REGDB_E_CLASSNOTREG`.

```powershell
dism.exe /online /enable-feature /featurename:Microsoft-Windows-Subsystem-Linux /all /norestart
dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart
```

**Reiniciar el equipo.** Después:

```powershell
wsl --set-default-version 2
wsl --install -d Ubuntu
```

## 2. Instalar Docker Desktop (PowerShell **como administrador**)

```powershell
winget install --id Docker.DockerDesktop -e --accept-package-agreements --accept-source-agreements
```

Al primer arranque, en Settings → Resources, dejar Docker con **8 GB de RAM
como máximo**. Con 16 GB totales, darle más deja al sistema sin aire.

Verificación:

```bash
docker run --rm hello-world
```

## 3. Verificar permisos en Azure

Esto sólo lo puedes comprobar tú, con tu cuenta.

1. Entra a [portal.azure.com](https://portal.azure.com) con la cuenta del instituto.
2. Busca **Microsoft Entra ID** → **App registrations** → **New registration**.

**Si el botón funciona:** perfecto, tienes permisos. Registra la app
"AgroTrack" y anota `Directory (tenant) ID` y `Application (client) ID`.

**Si aparece bloqueado o da error de permisos:** el tenant del instituto no te
deja. La salida es crear **tu propio tenant** gratis (Entra ID → Manage
tenants → Create), donde eres Global Admin y puedes hacer lo que quieras,
incluidos los App Roles (`ADMIN`, `OPERADOR`, `CLIENTE`, `AUDITOR`) que el
caso necesita.

**Averígualo hoy, no el martes.** Es la única dependencia externa del
proyecto que no está bajo tu control y la que más tarda en resolverse.

## 4. Comprobación final

Con todo lo anterior listo:

```bash
cd C:/Users/deint/Desktop/AgroTrack/infra
cp .env.example .env
docker compose -f local/compose.yml up -d
```

Oracle tarda ~3 minutos la primera vez en crear la base. Después:

- RabbitMQ Management → http://localhost:15672
- Kafka UI → http://localhost:8080
