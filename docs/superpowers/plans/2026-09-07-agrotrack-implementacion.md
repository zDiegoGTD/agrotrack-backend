# AgroTrack — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Plataforma completa de acopio y despacho agrícola según `Caso 1 - AgroTrack.docx`: 6 microservicios de dominio + 2 administradores de topología + BFF + frontend Angular, corriendo en local con Docker y desplegables en AWS.

**Architecture:** Cada microservicio Spring Boot es dueño de su esquema Oracle. `deliveries` ejecuta la máquina de estados (ya implementada) y, por cada transición, publica **hechos** en Kafka (`deliveries.events`) y **comandos** en RabbitMQ (`q.cmd.*`). `audit` y `report` consumen Kafka; `notify` consume RabbitMQ. Todo entra por `bff`, que valida el JWT de Azure AD y reenvía al servicio de dominio.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring Security 6 (resource server), Spring Data JPA + Flyway + Oracle Free 23, Spring AMQP, Spring Kafka, Testcontainers, Angular 22 + MSAL Angular 6, Docker Compose.

**Spec:** `docs/00-decisiones.md` … `docs/06-modelo-de-datos.md` (repo `docs`).

> **Estado al 2026-09-07 (noche):** Hitos A, B, C, D y E implementados y verificados
> (131 tests automatizados + smoke test de punta a punta en `infra/local/smoke.ps1`).
> Hito F: compose de las 3 EC2 y guia `infra/aws/README.md` listos; falta ejecutar el
> despliegue real (necesita las cuentas). Cambio respecto al plan: el motor es
> **PostgreSQL** (D1), no Oracle.

## Global Constraints

- Spring Boot `3.5.16`, Java `21`, Maven wrapper. No Boot 4 (D2).
- Estados sin tilde en código: `EN_CLASIFICACION` (D3).
- Un solo Oracle, cuatro esquemas `agro_deliveries`, `agro_catalog`, `agro_audit`, `agro_report` (D1). Sin FK entre esquemas.
- Esquema por **Flyway** (`db/migration/V*.sql`), `ddl-auto=validate`. Nunca `update`.
- Todo mensaje (Rabbit y Kafka) usa el **envelope** de `docs/02-contrato-de-eventos.md`. `eventId` UUID v7. Kafka con clave = `subject`.
- Kafka lleva hechos (pasado: `delivery.received`); Rabbit lleva comandos (`email.send`, `receipt.ticket`, `voucher.gen`).
- Consumidores idempotentes por tabla `PROCESSED_EVENTS`; ACK manual en Rabbit; 3 reintentos → DLQ/DLT.
- Roles del JWT: `ADMIN`, `OPERADOR`, `CLIENTE`, `AUDITOR` (claim `roles`), mapeados a `ROLE_*`.
- Perfiles Spring: `local` (Oracle/Rabbit/Kafka en localhost, JWT firmado con clave RSA local de desarrollo) y `aws` (Azure AD real). El código de seguridad es el mismo en ambos; solo cambia de dónde sale la clave pública.
- Cada servicio expone `/actuator/health` sin autenticación.
- Puertos locales: bff 8081, deliveries 8082, catalog 8083, notify 8084, report 8085, audit 8086, mq-admin 8087, kafka-admin 8088, frontend 4200. (Kafka UI ocupa 8080.)

---

## Estructura común de cada servicio

```
src/main/java/cl/agrotrack/<svc>/
  dominio/          reglas puras, sin Spring
  aplicacion/       casos de uso (@Service), DTOs de entrada/salida
  infraestructura/
    persistencia/   entidades JPA, repositorios
    web/            controladores REST, manejo de errores
    mensajeria/     productores/consumidores Rabbit/Kafka
  config/           SecurityConfig, JwtRolesConverter, propiedades
src/main/resources/
  application.yml, application-local.yml, application-aws.yml
  db/migration/V1__*.sql
src/test/java/...  tests unitarios + integración (Testcontainers)
```

**Seguridad (idéntica en cada servicio, ~40 líneas):** `SecurityConfig` como resource server JWT; `JwtRolesConverter` lee el claim `roles` y produce `ROLE_<rol>`; `/actuator/health` público; el resto autenticado; autorización por rol con `@PreAuthorize`. Duplicada a propósito para no acoplar repos con una librería compartida.

**Envelope (record, idéntico en cada servicio que lo usa):**

```java
public record Envelope(
    String specVersion, String type, String eventId, Instant occurredAt,
    String traceId, String correlationId, String source, String subject,
    Actor actor, Map<String, Object> data) {
  public record Actor(String userId, String role) {}
}
```

---

## Hito A — Núcleo de dominio: `catalog` y `deliveries`

### Task A1: catalog — esquema y entidades

**Files:** `ms-agrotrack-catalog/src/main/resources/db/migration/V1__catalog.sql`, entidades `Producto`, `Bodega` en `infraestructura/persistencia/`, repositorios Spring Data, `application*.yml`.
**Produces:** tablas `PRODUCTO`, `BODEGA` (con `VERSION` y `CHECK` de capacidad) según `06-modelo-de-datos.md`; `ProductoRepository`, `BodegaRepository`.

- [x] Test de integración `CatalogSchemaIT` con Testcontainers Oracle: el contexto arranca, Flyway aplica V1, `ddl-auto=validate` no falla.
- [x] Ejecutar → falla (no hay migración).
- [x] Escribir V1, entidades, repos, yml.
- [x] Ejecutar → pasa. Commit.

### Task A2: catalog — API de productos y bodegas

**Files:** `aplicacion/CatalogService`, DTOs, `infraestructura/web/ProductoController`, `BodegaController`, `ApiExceptionHandler`, `config/SecurityConfig`, `JwtRolesConverter`.
**Produces:**
- `GET/POST /api/catalog/productos`, `PUT /api/catalog/productos/{id}` (ADMIN escribe; ADMIN y OPERADOR leen). Alias `/api/catalog/services` (nombre del enunciado).
- `GET/POST /api/catalog/bodegas`, `PUT /api/catalog/bodegas/{id}`.
- `POST /api/catalog/bodegas/{id}/capacidad/reservar` y `/liberar` con body `{ "cantidad": 120.5, "entregaCodigo": "DEL-..." }` → 200 con capacidad restante, 409 si no alcanza. Uso interno de `deliveries` (roles ADMIN, OPERADOR).
- Bloqueo optimista en `Bodega` (`@Version`), reintento automático en `reservar` ante `OptimisticLockException`.

- [x] Tests `@WebMvcTest` con `@WithMockUser(roles=...)`: 403 para CLIENTE en POST, 201 para ADMIN, validación 400.
- [x] Test de integración de concurrencia: 20 reservas paralelas sobre una bodega de capacidad 100 → exactamente las que caben, sin pérdida.
- [x] Implementar. Commit.

### Task A3: deliveries — esquema, entidad y repositorio

**Files:** `V1__deliveries.sql` (`ENTREGA`, `PROCESSED_EVENTS`, índices, secuencia), entidad `Entrega`, `EntregaRepository` con `findByFiltros(estado, desde, hasta)`.
**Produces:** persistencia de `Entrega` con `codigo` `DEL-YYYY-NNNNNN` generado por secuencia.

- [x] `EntregaRepositoryIT` (Testcontainers): guardar, buscar por código, filtrar por estado y rango.
- [x] Implementar. Commit.

### Task A4: deliveries — casos de uso y API

**Files:** `aplicacion/EntregaService`, `aplicacion/CatalogClient` (RestClient hacia catalog, propaga el Bearer), `aplicacion/PublicadorEfectos` (interfaz; implementación de log en este hito), `infraestructura/web/EntregaController`, DTOs, `SecurityConfig`.
**Produces:**
- `POST /api/deliveries` body `{productoId, bodegaId, cantidad}` → 201 `{id, codigo, estado: REGISTRADA, ...}`. CLIENTE/OPERADOR/ADMIN. `productorId` = claim `oid` del JWT (o `sub`).
- `GET /api/deliveries/{id}`; CLIENTE solo ve las suyas.
- `PUT /api/deliveries/{id}/status` body `{status, pesoRecibido?, motivo?}` → evalúa `MaquinaEstadosEntrega`; 409 con `motivo` si no procede, 403 si el rol no puede; ejecuta efectos (`DESCONTAR/DEVOLVER_CAPACIDAD` vía `CatalogClient`, el resto vía `PublicadorEfectos`).
- `GET /api/deliveries?status=&from=&to=`.
- Interfaz `PublicadorEfectos { void publicar(Entrega e, EstadoEntrega anterior, Set<Efecto> efectos, Actor actor, String traceId); }`.

- [x] Tests de `EntregaService` con mocks de `CatalogClient` y `PublicadorEfectos`: transición válida llama a reservar capacidad; rechazo T7 llama a liberar; si catalog devuelve 409, la transición no se persiste.
- [x] `@WebMvcTest` del controlador: códigos HTTP correctos.
- [x] Implementar. Commit.

---

## Hito B — Comandos asíncronos: RabbitMQ

### Task B1: `ms-agrotrack-mq-admin` (nuevo repo)

**Produces:** al arrancar declara `cmd.direct`, `cmd.topic`, `cmd.dead.dlx`, las 3 colas con `x-dead-letter-exchange`/`x-dead-letter-routing-key` y sus 3 DLQ, bindings direct (`email.send`, `receipt.ticket`, `voucher.gen`) y topic (`email.*`, `receipt.#`, `voucher.*`). `GET /api/mq/topology` devuelve lo declarado. Repo con Dockerfile, en `apps/compose.yml`.

- [x] Test de integración con Testcontainers RabbitMQ: tras arrancar, las 6 colas y 3 exchanges existen (consulta vía `RabbitAdmin.getQueueInfo`).
- [x] Implementar con `Declarables`. Commit.

### Task B2: deliveries — publicar comandos en Rabbit

**Files:** `infraestructura/mensajeria/PublicadorRabbit implements PublicadorEfectos` (parcial: efectos de comando), `Envelope`, `EnvelopeFactory` (UUID v7, traceId desde MDC/cabecera `traceparent` o generado).
**Produces:** `NOTIFICAR_PRODUCTOR` → `cmd.direct`/`email.send`; `EMITIR_TICKET_BODEGA` → `receipt.ticket`; `GENERAR_GUIA_DESPACHO` → `voucher.gen`. Publicación **después del commit** (`TransactionSynchronization.afterCommit`).

- [x] Test con Testcontainers RabbitMQ: transición T2 deja un mensaje en `q.cmd.email` y otro en `q.cmd.receipt` con envelope válido.
- [x] Implementar. Commit.

### Task B3: notify — consumidores

**Files:** `EmailListener`, `ReceiptListener`, `VoucherListener`, `ProcessedEventsStore` (en memoria + archivo, no hay DB), `EmailSender` (JavaMailSender si hay SMTP configurado; si no, log), `VoucherPdf` (OpenPDF → `./vouchers/<codigo>.pdf`).
**Produces:** ACK manual; excepción → NACK sin requeue tras 3 intentos (retry con backoff en el contenedor de listeners) → DLQ. Métrica Micrometer `agrotrack.notify.dlq.total`.

- [x] Test: mensaje duplicado (mismo `eventId`) se procesa una sola vez. Mensaje malformado termina en la DLQ.
- [x] Implementar. Commit.

---

## Hito C — Streaming: Kafka

### Task C1: `ms-agrotrack-kafka-admin` (nuevo repo)

**Produces:** `NewTopic` para `deliveries.events` (3 particiones), `audit.timeline` (`cleanup.policy=compact,delete`, `retention.ms` 30 días), `deliveries.events.DLT`; réplicas = propiedad `agrotrack.kafka.replicas` (1 local, 3 aws). `GET /api/kafka/topics`.

- [x] Test con Testcontainers Kafka: los tres tópicos existen con la config esperada (`AdminClient.describeConfigs`).
- [x] Implementar. Commit.

### Task C2: deliveries — publicar hechos en Kafka

**Files:** `PublicadorKafka` (se compone con `PublicadorRabbit` en `PublicadorCompuesto`: primero Kafka, después Rabbit — D5).
**Produces:** un evento por transición (`delivery.registered|received|classifying|dispatching|dispatched|rejected`) en `deliveries.events`, clave = `codigo`, valor = envelope JSON.

- [x] Test con Testcontainers Kafka: T1 y T2 producen dos registros con la misma clave y `type` correcto.
- [x] Implementar. Commit.

### Task C3: audit — consumir y exponer timeline

**Files:** `V1__audit.sql` (`EVENTO_TIMELINE`, `PROCESSED_EVENTS`), `TimelineListener`, `TimelineRepository`, `AuditController`.
**Produces:** `GET /api/audit/deliveries/{codigo}/timeline`, `GET /api/audit/events?usuario=&desde=&hasta=&tipo=` (ADMIN, AUDITOR). Republica un resumen compactado en `audit.timeline` con clave `codigo`. `DefaultErrorHandler` con 3 reintentos y `DeadLetterPublishingRecoverer` → `deliveries.events.DLT`.

- [x] Test: consumir un envelope persiste una fila; el mismo `eventId` dos veces persiste una; un JSON inválido va al DLT.
- [x] Implementar. Commit.

### Task C4: report — agregaciones y KPIs

**Files:** `V1__report.sql` (`KPI_ENTREGAS_HORA`, `CICLO_ENTREGA`, `PROCESSED_EVENTS`), `KpiListener`, `ReportController`.
**Produces:** `GET /api/report/kpis?range=last24h` → `{entregasPorHora:[{hora,registradas,recibidas,despachadas,rechazadas}], tiempoCicloPromedioMin, estadosActivos:{REGISTRADA:n,...}}`; `GET /api/report/top-services?range=last7d` → productos más recibidos. Solo ADMIN.

- [x] Test: tres eventos `received` en la misma hora → fila con `TOTAL_RECIBIDAS=3`; `dispatched` cierra `CICLO_ENTREGA` con minutos correctos.
- [x] Implementar. Commit.

---

## Hito D — BFF y seguridad de punta a punta

### Task D1: clave JWT local y herramienta para emitir tokens

**Files:** `infra/local/jwt/generar-claves.mjs` (genera par RSA una vez), `infra/local/jwt/mint.mjs` (emite JWT RS256 con `roles`, `oid`, `name`, `aud`, `iss=http://localhost/local-issuer`), la clave pública copiada a `src/main/resources/jwt/local-public.pem` de cada servicio (perfil `local`).
**Produces:** `node infra/local/jwt/mint.mjs OPERADOR` imprime un token usable contra cualquier servicio en perfil `local`.

### Task D2: bff

**Files:** `ProxyController` (reenvía `/api/deliveries/**`, `/api/catalog/**`, `/api/report/**`, `/api/audit/**` al servicio correspondiente con el mismo Bearer), `MeController` (`GET /api/me` → nombre, oid, roles), `SecurityConfig` con reglas por ruta y rol, CORS para `http://localhost:4200`.
**Produces:** un solo punto de entrada; autorización por rol antes de reenviar (regla del enunciado: el BFF comprueba que el rol puede usar el endpoint).

- [x] `@WebMvcTest`: CLIENTE a `/api/report/**` → 403 sin llegar al proxy; AUDITOR a `PUT /api/deliveries/**` → 403; OPERADOR a `GET /api/deliveries` → reenvía (mock de RestClient).
- [x] Implementar. Commit.

### Task D3: prueba de humo de punta a punta (script)

**Files:** `infra/local/smoke.ps1`: levanta compose, arranca los servicios (`mvnw spring-boot:run` en background), emite tokens, registra una entrega como CLIENTE, la recibe como OPERADOR, verifica capacidad en catalog, el mail en el log de notify, el timeline en audit y el KPI en report.

---

## Hito E — Frontend Angular

### Task E1: MSAL, rutas y guardas

**Files:** `src/app/auth/` (config MSAL desde `environment.ts`: `clientId`, `authority`, `redirectUri`, scope `api://<API_CLIENT_ID>/access_as_user`), `roleGuard`, interceptor MSAL que adjunta el Bearer a `/api/**`; `environment.local.ts` con un modo `devToken` que usa un JWT pegado a mano (para trabajar sin Azure).
**Produces:** rutas `/login`, `/dashboard`, `/deliveries`, `/catalog`, `/reports`, `/audit` con los roles de la sección 6.

### Task E2: pantallas

- `Login`: botón «Iniciar sesión con Microsoft».
- `Dashboard`: por rol (Admin: KPIs; Operador: por recibir / en clasificación; Cliente: sus últimas entregas).
- `Entregas`: tabla con filtros estado/fechas, formulario de registro, acciones de cambio de estado según rol y transiciones válidas (usa la misma tabla de la máquina de estados, declarada en el front para habilitar/deshabilitar botones).
- `Catálogo`: productos y bodegas con capacidad, edición para ADMIN.
- `Reportería`: gráfico entregas por hora (SVG simple, sin librería), tiempo de ciclo, top productos.
- `Auditoría`: timeline con filtros usuario/fecha/tipo.

Tests: `*.spec.ts` de los servicios HTTP y del `roleGuard`; `ng build` en verde.

---

## Hito F — Despliegue

### Task F1: compose de apps con los 8 servicios y perfil `aws`; `infra/aws/README.md` con security groups, orden de arranque (kafka → mq → apps), variables por EC2; `.env.aws.example`.

---

## Dudas abiertas (no bloquean; se asume lo indicado)

| # | Duda | Supuesto mientras no se responda |
|---|---|---|
| 1 | ¿Puedo hacer `git push` yo a los repos? | Solo commits locales; push lo hace Diego |
| 2 | `TENANT_ID`, `CLIENT_ID` de Azure | Perfil `local` con JWT propio; `aws` parametrizado por variables |
| 3 | ¿SMTP real para los emails? | Si no hay `SPRING_MAIL_HOST`, notify escribe el email en el log |
| 4 | ¿Solo o en grupo? | Solo |
| 5 | ¿Oracle obligatorio? | Sí (enunciado) |
