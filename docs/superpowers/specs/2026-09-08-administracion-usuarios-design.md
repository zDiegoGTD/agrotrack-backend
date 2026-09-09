# Administración de usuarios — diseño

**Problema:** hoy quién puede entrar a AgroTrack lo decide únicamente Azure AD.
Para dar de alta a un productor hay que entrar al portal de Azure, y para
quitarle el acceso a alguien, también. La aplicación no tiene registro propio
ni forma de administrar a su gente.

**Objetivo:** que un usuario nuevo quede registrado al entrar por primera vez, y
que el administrador apruebe, rechace y desactive desde la web, con los datos
en PostgreSQL.

## Lo que NO cambia

**Los roles siguen viniendo del token de Azure.** Este módulo añade una capa de
autorización; no la reemplaza. La pauta del EP1 exige, en el indicador que vale
60%, que se *"lean roles y scopes desde los claims del token"* — moverlos a la
base de datos rompería eso.

La regla efectiva pasa a ser: **tu token trae el rol Y tu usuario está aprobado
en la base.** Un despedido deja de entrar aunque su cuenta de Microsoft siga
viva. Hoy eso no es posible.

## Arquitectura

Un microservicio nuevo, `ms-agrotrack-users`, con la misma forma que los demás:
base propia `agro_users` en la instancia PostgreSQL existente, resource server
JWT, contenedor en `apps/compose.yml`, expuesto por el BFF en `/api/users/**`.
Puerto 8089.

```
Angular ──► API Gateway ──► BFF ──┬──► users      (registro y estado)
                                   ├──► deliveries
                                   ├──► catalog
                                   └──► ...
```

El BFF consulta a `users` el estado de quien llama y bloquea antes de reenviar.
Es el único punto de entrada, así que basta con hacerlo ahí.

## Modelo de datos (esquema `agro_users`)

### `USUARIO`

| Columna | Tipo | Notas |
|---|---|---|
| `ID` | `BIGINT` PK | secuencia |
| `AZURE_OID` | `VARCHAR(50)` | **único**. El `oid` del token: el enlace con Azure |
| `EMAIL` | `VARCHAR(200)` | del claim `preferred_username` |
| `NOMBRE` | `VARCHAR(200)` | del claim `name` |
| `ESTADO` | `VARCHAR(20)` | `PENDIENTE` · `ACTIVO` · `RECHAZADO` · `INACTIVO` |
| `ROL_ULTIMO_TOKEN` | `VARCHAR(20)` | el rol con el que entró la última vez. **Informativo**: la autorización usa el token, no esta columna |
| `MOTIVO` | `VARCHAR(500)` | por qué se rechazó o desactivó |
| `PRIMER_INGRESO` | `TIMESTAMPTZ` | cuándo se registró |
| `ULTIMO_INGRESO` | `TIMESTAMPTZ` | |
| `APROBADO_POR` | `VARCHAR(50)` | `oid` del admin |
| `APROBADO_EN` | `TIMESTAMPTZ` | |
| `VERSION` | `BIGINT` | bloqueo optimista |

`ROL_ULTIMO_TOKEN` existe para que el administrador vea en la tabla qué rol
tiene cada uno sin salir de la aplicación. Que sea informativo se marca en el
nombre a propósito: si alguien la usara para autorizar, estaría autorizando con
datos de la última visita en vez de con el token de ahora.

### `PERFIL_PRODUCTOR`

| Columna | Tipo |
|---|---|
| `USUARIO_ID` | `BIGINT` PK/FK → `USUARIO` |
| `RUT` | `VARCHAR(15)` único |
| `RAZON_SOCIAL` | `VARCHAR(200)` |
| `TELEFONO` | `VARCHAR(30)` |
| `DIRECCION` | `VARCHAR(300)` |
| `BODEGA_HABITUAL_ID` | `BIGINT` (sin FK: vive en `catalog`) |

Tabla aparte y no columnas anulables en `USUARIO`: solo los productores tienen
ficha, y un auditor con doce columnas vacías es un modelo que miente.

## Máquina de estados del usuario

| Desde | Hacia | Quién | Cuándo |
|---|---|---|---|
| — | `PENDIENTE` | sistema | primer ingreso |
| — | `ACTIVO` | sistema | primer ingreso **si es el primer admin** (ver abajo) |
| `PENDIENTE` | `ACTIVO` | ADMIN | aprobar |
| `PENDIENTE` | `RECHAZADO` | ADMIN | rechazar (exige motivo) |
| `ACTIVO` | `INACTIVO` | ADMIN | desactivar (exige motivo) |
| `INACTIVO` | `ACTIVO` | ADMIN | reactivar |
| `RECHAZADO` | `ACTIVO` | ADMIN | reconsiderar |

Clase pura sin Spring ni JPA, igual que la máquina de estados de la entrega.

### El primer administrador

Si todos empiezan `PENDIENTE`, nadie puede aprobar a nadie: el sistema nace
bloqueado y el dueño se queda fuera.

**Regla:** mientras no exista **ningún** usuario `ACTIVO`, el primero que entre
con `ADMIN` en su token se crea ya `ACTIVO`, con `APROBADO_POR = 'sistema'`.
Del segundo en adelante, todos pasan por aprobación.

Es una excepción acotada y auditable —queda escrito quién y cuándo— y solo
puede ocurrir una vez en la vida del sistema.

### Un admin no puede desactivarse a sí mismo

Misma razón: si es el único, deja el sistema sin quien apruebe. Se rechaza con
409 y un mensaje explícito.

## Endpoints (`ms-agrotrack-users`)

| Método y ruta | Rol | Qué hace |
|---|---|---|
| `POST /api/users/sincronizar` | cualquiera autenticado | *Upsert* desde los claims. Crea `PENDIENTE` (o `ACTIVO` si es el primer admin), o actualiza nombre, correo, rol y último ingreso. Devuelve el usuario con su estado |
| `GET /api/users` | ADMIN | Lista, filtrable por `estado` |
| `GET /api/users/{id}` | ADMIN | Detalle con ficha |
| `PUT /api/users/{id}/estado` | ADMIN | `{ estado, motivo }` |
| `GET /api/users/me` | autenticado | Mi usuario y mi ficha |
| `PUT /api/users/{id}/perfil` | ADMIN, o el propio CLIENTE | Ficha del productor |

`sincronizar` es idempotente: llamarlo cien veces deja el mismo registro y solo
mueve `ULTIMO_INGRESO`.

## Cambios en el BFF

**1. `/api/me` sincroniza.** Ya existe y lo llama el frontend al entrar. Ahora
además invoca `POST /api/users/sincronizar` y devuelve, junto a los claims, el
`estado` del usuario. El registro ocurre solo, en un único lugar, sin que el
frontend tenga que acordarse de nada.

**2. Un filtro bloquea a quien no está activo.** Antes de reenviar cualquier
`/api/**` que no sea `/api/me`, comprueba el estado y responde `403` con un
código distinto por caso:

| Estado | Código | Mensaje |
|---|---|---|
| `PENDIENTE` | `CUENTA_PENDIENTE` | Tu cuenta espera aprobación |
| `RECHAZADO` | `CUENTA_RECHAZADA` | Tu solicitud fue rechazada |
| `INACTIVO` | `CUENTA_INACTIVA` | Tu cuenta fue desactivada |

**3. Caché de 60 segundos.** Sin ella habría una llamada extra a `users` en cada
petición. Con ella, desactivar a alguien surte efecto en menos de un minuto —
de sobra para esto, y el coste es una consulta por usuario por minuto.

Si `users` no responde, el BFF **deniega** (`503`). Ante la duda, no se deja
pasar: es una comprobación de seguridad.

## Pantallas

| Ruta | Rol | Contenido |
|---|---|---|
| `/usuarios` | ADMIN | Tabla: nombre, correo, rol del último token, estado, primer ingreso. Filtros por estado. Acciones aprobar / rechazar / desactivar / reactivar, con motivo cuando corresponde. Editar la ficha del productor |
| `/pendiente` | cualquiera no activo | Mensaje según el estado, con su correo y la fecha de solicitud. Sin menú: es una pantalla sin salida salvo cerrar sesión |
| `/mi-perfil` | CLIENTE | Su propia ficha, editable |

Una guarda nueva, `estadoGuard`, manda a `/pendiente` a quien no esté `ACTIVO`.

## Qué se prueba

**Sin infraestructura** (rápido): la máquina de estados del usuario, incluidas
las transiciones prohibidas y la regla del primer admin.

**Con PostgreSQL real** (Testcontainers):
- `sincronizar` dos veces con el mismo `oid` deja **un** registro y mueve solo `ULTIMO_INGRESO`
- El primer admin nace `ACTIVO`; **el segundo nace `PENDIENTE`** — es la prueba que demuestra que la excepción está acotada
- Un usuario sin rol admin nunca se auto-aprueba, aunque la tabla esté vacía
- `RUT` duplicado se rechaza

**BFF:** un `PENDIENTE` recibe 403 en `/api/deliveries` pero 200 en `/api/me`;
un `ACTIVO` pasa; si `users` no responde, 503; la caché no sirve un estado
viejo más de 60 s.

**Frontend:** `estadoGuard` redirige a `/pendiente`; la tabla de administración
muestra las acciones que corresponden a cada estado.

## Lo que este diseño deja fuera

**Cambiar el rol de alguien desde la web.** El rol vive en el token, y
modificarlo exige que el backend hable con Microsoft Graph, lo que a su vez
exige registrar una segunda aplicación en Azure con un secreto. Se puede añadir
encima de esto sin rehacer nada: el administrador ya tiene su pantalla y el
usuario ya tiene su fila.

Mientras tanto, los roles se asignan en el portal de Azure, y la aplicación
muestra cuál trae cada uno.
