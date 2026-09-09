# EP1 (DSY1107) — qué pide la pauta y cómo estamos

Fuente: `EP1_DSY1107_Estudiante_encargo.pdf`. Encargo = **40%** de la EP1, que a
su vez pesa **16%** de la asignatura. **En parejas.** Dos semanas.

> **Resuelto el 2026-09-08:** la pauta nombraba "Pedidos360" porque es una
> plantilla comun; a cada grupo se le asigno un caso distinto. El nuestro es
> **AgroTrack**, confirmado con el docente.

## Los dos indicadores que puntúan

| Indicador | Peso | Estado |
|---|---|---|
| MSAL + Angular: login/logout, guards, **MsalInterceptor**, tokens para el API Gateway, leer roles y scopes de los claims | **60%** | ✅ |
| BFF valida el token igual que el API Manager: issuer, audience, firma, vigencia, autorización por rol, códigos de error adecuados | **40%** | ✅ |

**Nada de Kafka, RabbitMQ, KPIs ni auditoría puntúa en el EP1.** Existen porque
el enunciado del caso los pide (y la EP2 seguramente los evalúa), pero para
esta nota lo que importa es la cadena de identidad de punta a punta.

### Cómo se cumple el 60%

| La pauta dice | Dónde está |
|---|---|
| MSAL integrado y operativo | `core/auth/msal-auth.service.ts` + `auth.providers.ts` |
| Inicio y cierre de sesión | `pages/login`, botón «Salir» en `shell/shell.ts` |
| Guards operan sin fallas | `core/auth/guards.ts` — `authGuard`, `roleGuard(...)` por ruta |
| **MsalInterceptor** opera sin fallas | `auth.providers.ts`: `HTTP_INTERCEPTORS → MsalInterceptor` con `protectedResourceMap` sobre `<apiUrl>/api/*` |
| Tokens para consumir el API Gateway | scope `api://<CLIENT_ID>/access_as_user`, el mismo audience que valida el Gateway |
| Leer roles y scopes desde los claims | `usuarioDesdeClaims()` lee `roles`, `oid`, `name`; `GET /api/me` los devuelve |

### Cómo se cumple el 40%

`ms-agrotrack-bff/config/SecurityConfig.java`, más los 9 tests de
`BffSeguridadTest`:

- **issuer y audience**: `issuer-uri` + `audiences` (acepta la forma v1
  `api://<id>` y la v2 `<id>`).
- **firma y vigencia**: Spring Security valida contra el JWKS del tenant; `exp`
  y `nbf` los comprueba el validador por defecto.
- **autorización por rol**: matriz explícita por ruta y método; lo que no está
  en la matriz se deniega (`anyRequest().denyAll()`).
- **códigos de error**: 401 sin token, 403 por rol, `problem+json` con detalle.
- Y el API Gateway valida **antes** que el BFF — el "al igual que el API
  Manager" de la pauta.

## Los 7 puntos de la demostración

| # | Requisito | Estado |
|---|---|---|
| 1 | Instancia de API Manager creada y funcionando | ✅ API Gateway `agrotrack-api` |
| 2 | Configuración que permite llamar a los endpoints del backend | ✅ HTTP proxy → `:8081/{proxy}`, ruta `ANY /api/{proxy+}` |
| 3 | El frontend consume los endpoints a través del API Manager | ✅ `environment.prod.ts` apunta a la Invoke URL |
| 4 | El API Manager valida JWT: rechaza inválidas, acepta correctas | ✅ rechazo verificado (401); aceptación requiere el login real |
| 5 | Tenant en IDaaS y **usuarios registrados** | ⚠️ tenant sí; **crear un usuario por rol** |
| 6 | El frontend usa OAuth 2.0 / OIDC y obtiene un JWT válido | ✅ MSAL, Authorization Code + PKCE |
| 7 | Backend y frontend desplegados, activos e integrados | ✅ verificado |

## Instrucciones formales

| La pauta pide | Estado |
|---|---|
| Backend = varios microservicios Java/Spring Boot | ✅ 8 |
| Frontend = componente Angular | ✅ Angular 22 |
| Entrega como enlaces a GitHub (a AVA + correo al docente) | ⏳ faltan `git push` y compartir con la pareja |
| El backend compila y responde a pruebas básicas | ✅ 132 tests |
| Frontend completo, modular, sin errores de compilación, vistas funcionales | ✅ 6 pantallas, build limpio |
| Integración con **base de datos cloud**: entidades, repositorios, propiedades de conexión | ⚠️ PostgreSQL en contenedor sobre EC2. **Preguntar si esperan RDS** |
| Filtros que validen el JWT del IDaaS | ✅ resource server en los 7 servicios con API |
| `.gitignore` para subir solo lo que corresponda | ✅ |

## Lo que falta, y de quién es

**Tuyo (portal de Azure, ~10 min):**
1. Redirect URI `http://54.84.179.128` en *Authentication → SPA*.
2. Crear 4 usuarios (uno por rol) y asignarles el rol correspondiente.
3. *Allow public client flows* = Sí, si quieres usar `smoke-aws.ps1`.

**Tuyo (con el docente):**
4. ¿AgroTrack o Pedidos360?
5. ¿"Base de datos cloud" significa RDS?
6. Quién es tu pareja (hay que darle acceso a los repos).

**Tuyo (GitHub):** `git push` de todo y crear los 2 repos que faltan
(`ms-agrotrack-mq-admin`, `ms-agrotrack-kafka-admin`). Ver `05-repositorios.md`.
