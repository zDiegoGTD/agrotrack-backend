# Modelo de datos

> **Motor: PostgreSQL 16** (D1). Los tipos de las tablas de abajo se
> escribieron para Oracle antes del cambio; el DDL real esta en los
> `V1__*.sql` de cada servicio (`NUMBER` -> `NUMERIC`/`BIGINT`,
> `VARCHAR2` -> `VARCHAR`, `NUMBER(1)` -> `BOOLEAN`, `TIMESTAMP WITH TIME
> ZONE` -> `TIMESTAMPTZ`, `CLOB` -> `JSONB`). "Esquema" aqui significa
> "base de datos propia del servicio".

Cada servicio es dueño de sus tablas. Nadie lee las tablas de otro, ni
siquiera estando en la misma instancia Oracle (decisión D1).

---

## Esquema `agro_catalog` — propiedad de `ms-agrotrack-catalog`

### `PRODUCTO`

| Columna | Tipo | Notas |
|---|---|---|
| `ID` | `NUMBER(19)` PK | generado por secuencia |
| `CODIGO` | `VARCHAR2(30)` | único, legible: `TRIGO-01` |
| `NOMBRE` | `VARCHAR2(120)` | |
| `UNIDAD_MEDIDA` | `VARCHAR2(10)` | KG, TON, QQ |
| `TARIFA` | `NUMBER(12,2)` | el enunciado la pide en el catálogo |
| `ACTIVO` | `NUMBER(1)` | baja lógica, nunca `DELETE` |

Los productos se dan de baja, no se borran: una entrega vieja tiene que
poder seguir mostrando qué producto era.

### `BODEGA`

| Columna | Tipo | Notas |
|---|---|---|
| `ID` | `NUMBER(19)` PK | |
| `NOMBRE` | `VARCHAR2(120)` | |
| `UBICACION` | `VARCHAR2(200)` | la cooperativa o centro de acopio |
| `CAPACIDAD_TOTAL` | `NUMBER(12,2)` | |
| `CAPACIDAD_DISPONIBLE` | `NUMBER(12,2)` | baja al RECIBIR, sube al RECHAZAR |
| `VERSION` | `NUMBER(19)` | **bloqueo optimista, obligatorio** |

**`VERSION` no es opcional.** Dos jefes de acopio recibiendo lotes al mismo
tiempo en la misma bodega es el caso real, no el excepcional. Sin bloqueo
optimista, las dos transacciones leen la misma capacidad, las dos restan
sobre ese valor y una de las dos restas se pierde: la bodega queda con más
espacio del que realmente tiene. Con `@Version`, la segunda transacción
falla, se reintenta y suma bien.

**Invariante:** `0 <= CAPACIDAD_DISPONIBLE <= CAPACIDAD_TOTAL`, garantizada
con un `CHECK` en la tabla además de la validación en código. La base es la
última línea de defensa cuando el código tiene un bug.

---

## Esquema `agro_deliveries` — propiedad de `ms-agrotrack-deliveries`

### `ENTREGA`

| Columna | Tipo | Notas |
|---|---|---|
| `ID` | `NUMBER(19)` PK | |
| `CODIGO` | `VARCHAR2(30)` | único, el que ve el usuario: `DEL-2026-000148` |
| `PRODUCTOR_ID` | `VARCHAR2(50)` | el `oid` del JWT de Azure AD |
| `PRODUCTO_ID` | `NUMBER(19)` | **sin FK**, ver abajo |
| `BODEGA_ID` | `NUMBER(19)` | **sin FK** |
| `CANTIDAD` | `NUMBER(12,2)` | declarada al registrar |
| `PESO_RECIBIDO` | `NUMBER(12,2)` | real, al pesar. Nulo hasta RECIBIDA |
| `ESTADO` | `VARCHAR2(20)` | el enum, sin tilde |
| `MOTIVO_RECHAZO` | `VARCHAR2(500)` | nulo salvo en RECHAZADA |
| `FECHA_REGISTRO` | `TIMESTAMP WITH TIME ZONE` | |
| `FECHA_RECEPCION` | `TIMESTAMP WITH TIME ZONE` | nulo hasta RECIBIDA |
| `FECHA_DESPACHO` | `TIMESTAMP WITH TIME ZONE` | nulo hasta DESPACHADA |
| `VERSION` | `NUMBER(19)` | bloqueo optimista |

**Por qué `PRODUCTO_ID` y `BODEGA_ID` no llevan clave foránea:** viven en
otro servicio. Una FK entre esquemas ataría `deliveries` a la estructura
interna de `catalog` — si mañana catalog cambia sus tablas o se mueve a otra
base, deliveries se rompe. La validación de que el producto existe se hace
llamando a catalog al registrar, no delegándola al motor.

Es una pérdida real de integridad referencial, asumida a cambio de que los
servicios sean independientes. Es el trade-off central de microservicios y
conviene poder explicarlo si lo preguntan.

**Las tres fechas separadas** (registro, recepción, despacho) no son
redundancia: de ellas sale el **tiempo de ciclo** que pide el panel de KPIs
(sección 3 del enunciado). Guardarlas en el momento de la transición es
gratis; reconstruirlas después desde el timeline, no.

**Índices:**

| Índice | Para qué |
|---|---|
| `UK_ENTREGA_CODIGO` (único) | búsqueda por código |
| `IX_ENTREGA_ESTADO_FECHA` (`ESTADO`, `FECHA_REGISTRO`) | `GET /api/deliveries?status=...&from=...&to=...`, que es el filtro del enunciado |
| `IX_ENTREGA_PRODUCTOR` (`PRODUCTOR_ID`) | el productor viendo sus propias entregas |

### `PROCESSED_EVENTS`

| Columna | Tipo |
|---|---|
| `EVENT_ID` | `VARCHAR2(36)` PK |
| `PROCESSED_AT` | `TIMESTAMP` |

La tabla de idempotencia de `02-contrato-de-eventos.md`. **Va en todo
servicio que consuma mensajes** — notify, audit y report también. La PK
haciendo el trabajo: si el `INSERT` choca, el mensaje ya se procesó.

---

## Esquema `agro_audit` — propiedad de `ms-agrotrack-audit`

### `EVENTO_TIMELINE`

| Columna | Tipo | Notas |
|---|---|---|
| `ID` | `NUMBER(19)` PK | |
| `EVENT_ID` | `VARCHAR2(36)` | único, del envelope |
| `ENTREGA_CODIGO` | `VARCHAR2(30)` | el `subject` del evento |
| `TIPO` | `VARCHAR2(50)` | `delivery.received`, etc. |
| `ACTOR_ID` | `VARCHAR2(50)` | quién lo hizo |
| `ACTOR_ROL` | `VARCHAR2(20)` | |
| `OCURRIDO_EN` | `TIMESTAMP WITH TIME ZONE` | |
| `TRACE_ID` | `VARCHAR2(64)` | |
| `PAYLOAD` | `CLOB` | el `data` crudo, en JSON |

**Esta tabla solo recibe INSERT.** Nunca `UPDATE` ni `DELETE` — un registro
de auditoría que se puede editar no es auditoría. Si la pauta lo pide, se
puede reforzar quitándole esos permisos al usuario de base de datos.

`PAYLOAD` como CLOB con el JSON completo: los campos que hoy no se
consultan igual quedan guardados. Un timeline al que le falta información
no sirve, y no sabemos hoy qué va a hacer falta mañana.

**Índice:** `IX_TIMELINE_ENTREGA` (`ENTREGA_CODIGO`, `OCURRIDO_EN`) — el
timeline de una entrega en orden es *la* consulta de este servicio.

---

## Esquema `agro_report` — propiedad de `ms-agrotrack-report`

Agregaciones precalculadas, no consultas sobre los datos crudos.

### `KPI_ENTREGAS_HORA`

| Columna | Tipo |
|---|---|
| `HORA` | `TIMESTAMP` PK (truncada a la hora) |
| `BODEGA_ID` | `NUMBER(19)` PK |
| `TOTAL_REGISTRADAS` | `NUMBER(10)` |
| `TOTAL_RECIBIDAS` | `NUMBER(10)` |
| `TOTAL_DESPACHADAS` | `NUMBER(10)` |
| `TOTAL_RECHAZADAS` | `NUMBER(10)` |

### `CICLO_ENTREGA`

| Columna | Tipo | Notas |
|---|---|---|
| `ENTREGA_CODIGO` | `VARCHAR2(30)` PK | |
| `BODEGA_ID` | `NUMBER(19)` | |
| `PRODUCTO_ID` | `NUMBER(19)` | para "productos más recibidos" |
| `MINUTOS_CICLO` | `NUMBER(10)` | registro → despacho |
| `FECHA_CIERRE` | `TIMESTAMP` | |

**Por qué agregar en vez de consultar:** el enunciado pide el panel "en
tiempo real" y "sin bloquear el core". Si reportería hiciera `COUNT(*)`
sobre las entregas cada vez que alguien abre el dashboard, estaría
compitiendo por los mismos datos que el core necesita para operar. Como
report consume Kafka, puede ir sumando a medida que los eventos llegan y
servir el panel con un `SELECT` trivial.

---

## Cómo se crean las tablas

**No con `ddl-auto=update`.** Está bien para el primer día y es un desastre
después: no versiona nada, no se puede revisar en un pull request, y hace
cosas distintas según el estado en que encuentre la base.

Propuesta: **Flyway**, con migraciones numeradas en
`src/main/resources/db/migration/`. Cada cambio de esquema es un archivo
`.sql` en git, se aplica solo al arrancar, y la base queda igual en tu PC y
en la EC2.

`ddl-auto` queda en `validate`: arranca solo si el esquema coincide con las
entidades, y falla de inmediato si alguien las desincronizó.

**Pendiente de confirmar el martes**, junto con el motor de base de datos.
Si la pauta pide Flyway o Liquibase explícitamente, ya está decidido.
