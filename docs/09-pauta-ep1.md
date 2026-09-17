# EP1 (DSY1107) — qué pide la pauta y dónde se cumple

Fuente: `EP1_DSY1107_Estudiante_encargo.pdf`. Encargo = **40%** de la EP1, que a
su vez pesa **16%** de la asignatura. **En parejas** (Diego Otárola y Lian
Peralta).

Caso asignado: **AgroTrack** (la pauta nombra "Pedidos360" porque es una
plantilla común; confirmado con el docente el 2026-09-08).

## Los dos indicadores que puntúan

| Indicador | Peso | Estado |
|---|---|---|
| MSAL + Angular: login/logout, guards, **MsalInterceptor**, tokens para el API Gateway, leer roles y scopes de los claims | **60%** | ✅ |
| BFF valida el token igual que el API Manager: issuer, audience, firma, vigencia, autorización por rol, códigos de error adecuados | **40%** | ✅ |

### 60% — Frontend Angular con MSAL (repo `frontend-agrotrack`)

| La pauta dice | Dónde está |
|---|---|
| MSAL integrado y operativo | `core/auth/msal-auth.service.ts` + `auth.providers.ts` (`PublicClientApplication`, Authorization Code + PKCE) |
| Inicio y cierre de sesión | `pages/login` (`loginRedirect`) y «Salir» en `shell/shell.ts` (`logoutRedirect`) |
| Guards operan sin fallas | `core/auth/guards.ts`: `authGuard` (sesión), `roleGuard(...)` (rol por ruta) y `estadoGuard` (cuenta aprobada) |
| **MsalInterceptor** opera sin fallas | `auth.providers.ts`: `HTTP_INTERCEPTORS → MsalInterceptor` con `protectedResourceMap` sobre `<apiUrl>/api/*` |
| Tokens para consumir el API Gateway | scope `api://<CLIENT_ID>/access_as_user`: el mismo que exigen el Gateway y el BFF |
| Leer **roles y scopes** desde los claims | `usuarioDesdeClaims()` lee `roles`; la pantalla **Mi sesión** (`/sesion`) muestra `roles`, `scp`, `iss`, `aud`, `ver`, `iat` y `exp`, y prueba la autorización en vivo contra el backend |

### 40% — El BFF valida el token (repo `agrotrack-backend`, `ms-agrotrack-bff`)

| La pauta dice | Cómo | Prueba |
|---|---|---|
| **Issuer** | `issuer-uri` = `https://login.microsoftonline.com/<TENANT>/v2.0` | `ValidacionJwtTest.emisorAjeno` → 401 `EMISOR_INVALIDO` |
| **Audience** | `audiences` = `api://<CLIENT_ID>,<CLIENT_ID>` | `audienciaAjena` → 401 `AUDIENCIA_INVALIDA`; `audienciaV1` → 200 |
| **Firma** | llaves públicas (JWKS) del tenant | `firmaFalsa` y `manipulado` → 401 `FIRMA_INVALIDA` |
| **Vigencia** | `exp` y `nbf` (validador por defecto de Spring) | `vencido` → 401 `TOKEN_VENCIDO`; `aunNoVigente` → 401 |
| **Scope** | `ValidacionTokenConfig`: exige `scp` = `access_as_user` en AWS | `sinScope` → 401 `SCOPE_INSUFICIENTE` |
| **Autorización por rol** | matriz por ruta y método en `SecurityConfig`; lo que no está, se niega | `rolInsuficiente` → 403 `ROL_INSUFICIENTE`; `BffSeguridadTest` (9 casos) |
| **Códigos de error adecuados** | `RespuestasSeguridad`: 401/403 con `WWW-Authenticate` (RFC 6750) **y** cuerpo `problem+json` (RFC 7807) con el motivo | todos los casos anteriores verifican status, cabecera y `codigo` |

`ValidacionJwtTest` usa **tokens reales firmados** con la forma de los de Azure
AD y la **misma configuración que el perfil `aws`**: un servidor local hace de
Azure sirviendo el descubrimiento OIDC y las llaves. No usa el atajo `jwt()` de
spring-security-test, que se salta la validación.

Además del BFF, cada microservicio valida el token por su cuenta (defensa en
profundidad) y el API Gateway lo valida **antes** que todos.

## Los 7 puntos de la demostración

Guion paso a paso en [`10-checklist-demo.md`](10-checklist-demo.md).

| # | Requisito | Cómo se muestra |
|---|---|---|
| 1 | Instancia de API Manager creada y funcionando | API Gateway `agrotrack-api` (HTTP API) |
| 2 | Configuración para llamar a los endpoints del backend | Ruta `ANY /api/{proxy+}` → integración HTTP al BFF, con JWT Authorizer y scope `access_as_user` |
| 3 | El frontend consume a través del API Manager | Network: todo va a la Invoke URL, con `Authorization: Bearer` puesto por el MsalInterceptor |
| 4 | El API Manager valida JWT: rechaza inválidas, acepta correctas | `infra/aws/probar-jwt.ps1`: sin token, basura, firma inventada, token alterado → 401; token real → 200; directo a la EC2 → 403 |
| 5 | Tenant en IDaaS con usuarios registrados | Entra ID, tenant **Mish**: 5 usuarios con App Roles `ADMIN`, `OPERADOR`, `CLIENTE`, `AUDITOR`; *Assignment required* = Sí |
| 6 | Login OAuth 2.0 / OIDC y JWT válido | MSAL con Authorization Code + PKCE; claims visibles en **Mi sesión** |
| 7 | Backend y frontend desplegados, activos e integrados | 3 EC2 (apps, Kafka, RabbitMQ): 9 microservicios + frontend + PostgreSQL, Kafka 3×3, RabbitMQ en clúster |

## Instrucciones formales

| La pauta pide | Estado |
|---|---|
| Backend = varios microservicios Java/Spring Boot | ✅ 9 (bff, deliveries, catalog, notify, audit, report, users, mq-admin, kafka-admin) |
| Frontend = componente Angular | ✅ Angular 22 |
| Entrega como enlaces a GitHub | ✅ `agrotrack-backend` y `frontend-agrotrack`, públicos |
| El backend compila y responde a pruebas básicas | ✅ tests por servicio (JUnit + Testcontainers) y smoke de punta a punta |
| Frontend completo, modular, sin errores de compilación | ✅ build de producción limpio, tests con Vitest |
| Integración con base de datos: entidades, repositorios, propiedades de conexión | ✅ **PostgreSQL** con JPA + Flyway, una base por servicio. El docente no entregó cuentas de base de datos cloud y aceptó PostgreSQL en contenedor (ver `00-decisiones.md`, D1) |
| Filtros que validen el JWT del IDaaS | ✅ resource server en todos los servicios con API |
| `.gitignore` para subir solo lo que corresponda | ✅ secretos (`.env`, `.env.aws`, IPs) fuera del repo |
