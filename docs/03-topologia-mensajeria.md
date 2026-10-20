# Topología de mensajería

## RabbitMQ — 3 flujos + 3 DLQ

**Exchanges:** `cmd.direct` (direct), `cmd.topic` (topic), `cmd.dead.dlx` (direct).

| Cola | Binding direct | Binding topic | DLQ | Quién consume |
|---|---|---|---|---|
| `q.cmd.email` | `email.send` | `email.*` | `q.cmd.email.dlq` | `ms-agrotrack-notify` |
| `q.cmd.receipt` | `receipt.ticket` | `receipt.#` | `q.cmd.receipt.dlq` | `ms-agrotrack-notify` |
| `q.cmd.voucher` | `voucher.gen` | `voucher.*` | `q.cmd.voucher.dlq` | `ms-agrotrack-notify` |

Cada cola principal declara `x-dead-letter-exchange: cmd.dead.dlx` y su
routing key hacia la DLQ que le corresponde.

### Qué transición dispara qué comando

| Transición | `q.cmd.email` | `q.cmd.receipt` | `q.cmd.voucher` |
|---|---|---|---|
| T2 recibir | ✅ "tu lote fue recibido" | ✅ ticket de pesaje a bodega | — |
| T3 clasificar | ✅ "tu lote está en clasificación" | — | — |
| T5 despachar | ✅ "tu lote fue despachado" | — | ✅ guía de despacho PDF |
| T6/T7/T8 rechazar | ✅ "tu lote fue rechazado: {motivo}" | — | — |

## Kafka

| Tópico | Particiones | Réplicas | Política | Retención | Clave |
|---|---|---|---|---|---|
| `deliveries.events` | 3 | 3 | delete | 7 días | `subject` (id de entrega) |
| `audit.timeline` | 3 | 3 | compact,delete | 30 días | `subject` |
| `deliveries.events.DLT` | 3 | 3 | delete | 14 días | `subject` |

**Por qué la clave es el id de la entrega:** Kafka garantiza orden *dentro de
una partición*, no entre particiones. Si los eventos de una misma entrega se
repartieran al azar, la auditoría podría registrar "despachada" antes que
"recibida". Con `subject` como clave, todos los eventos de una entrega caen
siempre en la misma partición y llegan en orden.

**Por qué `audit.timeline` es `compact,delete`:** compactación mantiene el
último estado conocido por clave (útil para reconstruir el estado actual sin
releer todo), y `delete` con 30 días evita que crezca sin límite.

**Consumer groups:** `audit-consumer` y `report-consumer`, separados. Cada uno
lee `deliveries.events` completo y a su propio ritmo. Si reportería se cae,
auditoría no se entera — que es exactamente el punto de usar Kafka aquí.

## Los dos servicios "administradores"

El enunciado pide "un microservicio administrador de RabbitMQ y otro de Kafka"
sin decir qué hacen. Interpretación adoptada:

> **Declaran la topología al arrancar, desde código versionado en git.**

Es decir: exchanges, colas, bindings y DLQ en el caso de RabbitMQ; tópicos con
sus particiones, réplicas, política y retención en el de Kafka. Nada se
configura a mano por la Management UI ni por `kafka-topics.sh`.

Dos razones concretas:

1. Con AWS Academy las instancias se apagan solas. Todo lo que se haya
   clickeado a mano en una UI se pierde. Lo declarado en código vuelve solo.
2. La pauta va a pedir demostrar la topología. Un archivo en git es evidencia;
   un screenshot de la UI no.

La pauta del EP3 confirmó y amplió esta interpretación: el administrador de
RabbitMQ, además de declarar la topología del sistema, **expone una API REST
para crear y eliminar colas, exchanges y bindings**.

## API de administración de RabbitMQ (`ms-agrotrack-mq-admin`)

Toda la lógica vive en `RabbitAdminService`; los controladores solo traducen
HTTP y no conocen `AmqpAdmin`. Crear y borrar es solo para **ADMIN** (en el
BFF y en el propio servicio); `OPERADOR` puede consultar la topología.

| Método | Ruta | Qué hace | Respuesta |
|---|---|---|---|
| `GET` | `/api/mq/topology` | Topología del sistema con mensajes y consumidores por cola; `totalEnDlq` > 0 es alarma | 200 |
| `POST` | `/api/mq/queues` | Crea una cola; por defecto durable y **con su DLQ** (`<cola>.dlq` enrutada por `cmd.dead.dlx`) | 201 + `Location` |
| `GET` | `/api/mq/queues/{nombre}` | Estado de una cola | 200 |
| `DELETE` | `/api/mq/queues/{nombre}` | Elimina una cola | 204 |
| `DELETE` | `/api/mq/queues/{nombre}/mensajes` | Purga (vacía sin borrar), p. ej. una DLQ ya reprocesada | 204 |
| `POST` | `/api/mq/exchanges` | Crea un exchange `DIRECT`, `TOPIC` o `FANOUT` | 201 |
| `DELETE` | `/api/mq/exchanges/{nombre}` | Elimina un exchange | 204 |
| `POST` | `/api/mq/bindings` | Une cola y exchange con una routing key (admite `*` y `#`) | 201 |
| `DELETE` | `/api/mq/bindings` | Deshace la unión (mismo cuerpo que el POST) | 204 |
| `GET` | `/api/mq/protegidos` | Recursos del sistema que la API no deja tocar | 200 |

**Validación de entrada** (DTOs con Bean Validation): nombres obligatorios, de
hasta 200 caracteres, solo letras, números, `.`, `-` y `_`, y sin el prefijo
`amq.` reservado por RabbitMQ; el tipo de exchange debe ser uno de los tres
admitidos. Un dato inválido responde **400** con `codigo: DATOS_INVALIDOS` y
el motivo de cada campo, sin llegar al broker.

**Errores con código** (problem+json):

| Situación | HTTP | `codigo` |
|---|---|---|
| Tocar una cola, DLQ o exchange de la topología del sistema | 403 | `RECURSO_DEL_SISTEMA` |
| Borrar o unir algo que no existe | 404 | `NO_EXISTE` |
| Redeclarar con otros atributos (`PRECONDITION_FAILED`) | 409 | `CONFLICTO_CON_EXISTENTE` |
| Otro rechazo del broker | 502 | `BROKER_RECHAZO` |

Las colas y exchanges del sistema están protegidos porque borrarlos en caliente
haría que los comandos de notify se perdieran sin aviso; esa topología solo
cambia en `TopologiaRabbit`, versionada en git.

## Confirmación de mensajes en los consumidores (`ms-agrotrack-notify`)

`acknowledge-mode: manual`: cada mensaje se confirma o rechaza explícitamente
en `ProcesadorComandos`, compartido por los tres consumidores, uno por dominio
funcional: `EmailListener` (notificaciones), `TicketListener` (recepción en
bodega) y `VoucherListener` (despacho).

| Situación | Decisión | Resultado |
|---|---|---|
| Se ejecutó bien | `basicAck` | sale de la cola |
| `eventId` ya procesado (reentrega) | `basicAck` | sale sin repetir el efecto |
| Mensaje inválido: JSON roto, sin `eventId`, `type` o `subject` | `basicNack(requeue=false)` inmediato | **DLQ** sin reintentar |
| Fallo transitorio (SMTP caído, disco lleno) | reintento tras 1 s y 4 s | sigue en proceso |
| Reintentos agotados (3 intentos) | `basicNack(requeue=false)` | **DLQ**, con log `[dlq]` y métrica `agrotrack.notify.dlq.total` |
| El servicio se apaga a mitad de una espera | `basicNack(requeue=true)` | vuelve a la cola para otra instancia |

Nunca se rechaza con `requeue=true` ante un error del mensaje: volvería al
frente de la cola y la bloquearía en un bucle infinito. Los reintentos son
acotados y ocurren en el consumidor.

## Nombres centralizados

Ningún nombre de cola, exchange o routing key está escrito en las clases de
negocio:

| Servicio | Dónde |
|---|---|
| mq-admin | `TopologiaRabbit`, clase `@Configuration` única con exchanges, colas, DLQ y bindings |
| deliveries | `application.yml` → `agrotrack.rabbit.exchange-comandos` y `agrotrack.rabbit.comandos` (efecto → routing key) |
| notify | `application.yml` → `agrotrack.rabbit.colas` (`@RabbitListener(queues = "${...}")`) y `agrotrack.notify.reintentos` |
