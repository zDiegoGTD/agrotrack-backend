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
| `audit.timeline.DLT` | 3 | 3 | delete | 14 días | código de la entrega |

| Tópico | Productor (DTO) | Consumidores (grupo) |
|---|---|---|
| `deliveries.events` | deliveries, `Envelope` | audit (`audit-consumer`), report (`report-consumer`) |
| `audit.timeline` | audit, `ResumenTimeline` | report (`report-timeline`) |
| `*.DLT` | el `DeadLetterPublishingRecoverer` de cada consumidor | se inspeccionan en Kafka UI |

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
`report-timeline` lee `audit.timeline` en un grupo aparte, para que su avance
no dependa del de `report-consumer`. Como `audit.timeline` es compactado, la
actividad por rol cuenta lo leído desde que el grupo empezó; releer desde el
inicio solo trae el último resumen por entrega (la historia completa está en audit).

Dentro de un grupo, cada listener corre **3 hilos** (`concurrency`, uno por
partición). Con una sola instancia, cada hilo toma una partición; si se
levanta una segunda instancia del mismo servicio, Kafka reparte las 3
particiones entre las dos (balanceo) y, si una cae, la otra retoma sus
particiones desde el último offset confirmado (resiliencia).

### Mensajes tipados y errores

- **Productores:** el valor viaja como objeto (`Envelope`, `ResumenTimeline`)
  con `JsonSerializer` y el `ObjectMapper` de Spring (fechas ISO-8601), sin
  cabecera de tipo Java. Cliente idempotente: `acks=all`,
  `enable.idempotence=true`, compresión `lz4`, reintentos acotados por
  `delivery.timeout.ms`.
- **Consumidores:** `ErrorHandlingDeserializer` + `JsonDeserializer`. Report
  elige `Envelope` o `ResumenTimeline` según el tópico. `max.poll.records=100`,
  client-id propio por listener, offsets confirmados por registro (`ack-mode: record`).
- **Errores** (`config/KafkaConfig` de audit y report):

| Situación | Qué pasa |
|---|---|
| El cuerpo no es JSON | `DeserializationException`: no se reintenta, va a `<tópico>.DLT` con sus bytes originales y el consumidor sigue con el siguiente |
| JSON válido pero sin `eventId`, `type`, `subject` u `occurredAt` | `MensajeInvalidoException`: no se reintenta, va a la DLT |
| Falla transitoria (la base caída) | 3 intentos con 1 s entre ellos; después, a la DLT |
| Reentrega de un evento ya aplicado | se descarta por `processed_events` (idempotencia) |

Cada envío a la DLT deja el log `[dlt] clave@offset de tópico (partición) -> DLT. Causa: ...`
y cabeceras `kafka_dlt-*` con la excepción, el tópico, la partición y el offset de origen.

## API de administración de Kafka (`ms-agrotrack-kafka-admin`)

Toda la lógica vive en `KafkaAdminService` (AdminClient); los controladores
solo traducen HTTP. Escribir es solo **ADMIN** (en el BFF y en el servicio);
`OPERADOR` puede consultar.

| Método | Ruta | Qué hace | Respuesta |
|---|---|---|---|
| `GET` | `/api/kafka/topics` | Todos los tópicos (sin los internos) con particiones, réplicas y configuración | 200 |
| `POST` | `/api/kafka/topics` | Crea un tópico: nombre, particiones (1-50), réplicas (1-3) y configuración | 201 + `Location` |
| `GET` | `/api/kafka/topics/{nombre}` | Estado de un tópico | 200 |
| `PATCH` | `/api/kafka/topics/{nombre}/config` | Cambia configuración (`retention.ms`, `cleanup.policy`...); valor vacío = volver al default | 200 |
| `POST` | `/api/kafka/topics/{nombre}/particiones` | Aumenta particiones (Kafka no permite bajarlas) | 200 |
| `DELETE` | `/api/kafka/topics/{nombre}` | Elimina un tópico | 204 |
| `GET` | `/api/kafka/consumer-groups` | Grupos con estado, miembros y **lag** por tópico | 200 |
| `GET` | `/api/kafka/consumer-groups/{grupo}` | Un grupo | 200 |
| `POST` | `/api/kafka/consumer-groups/{grupo}/topics/{tópico}/offsets` | Mueve los offsets al `INICIO` (reprocesar) o al `FIN` (saltar lo pendiente); solo con el grupo detenido | 200 |
| `GET` | `/api/kafka/protegidos` | Tópicos del sistema | 200 |

Los tópicos del sistema no se borran ni se reparticionan (cambiar las
particiones cambiaría a qué partición va cada entrega y rompería su orden);
solo se les puede ajustar `retention.ms`. Errores con el mismo `codigo` que
mq-admin: `DATOS_INVALIDOS` 400, `RECURSO_DEL_SISTEMA` 403, `NO_EXISTE` 404,
`CONFLICTO_CON_EXISTENTE` 409 (ya existe, más réplicas que brokers, grupo
activo), `BROKER_RECHAZO` 502.

Prueba en vivo: [`infra/aws/probar-kafka-admin.ps1`](../infra/aws/probar-kafka-admin.ps1) (15 casos, `-Pausa` para mirarlo en Kafka UI).

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

## Mensajes no entregados en el productor (`ms-agrotrack-deliveries`)

La DLQ cubre lo que llegó a una cola y falló al procesarse. Lo que **nunca llega a
una cola** se registra en el productor, en `RegistroNoEntregados`:

| Situación | Aviso de RabbitMQ | Qué queda |
|---|---|---|
| El exchange existe pero ninguna cola tiene binding para esa routing key | *return* (se publica con `mandatory=true`; sin eso el broker lo descartaría en silencio) | log `[rabbit] NO ENRUTADO` con eventId, exchange, routing key y el cuerpo completo |
| El broker no puede hacerse cargo (exchange inexistente, error interno) | *publisher confirm* con NACK | log `[rabbit] RECHAZADO POR EL BROKER` con eventId, causa y cuerpo |
| El broker no responde | excepción al publicar | log `[rabbit] NO PUBLICADO` con el cuerpo |

Los dos primeros se cuentan en la métrica `agrotrack.deliveries.comandos.no_entregados`.
En los tres casos el cuerpo queda en el log para reinyectarlo.

## Nombres centralizados

Ningún nombre de cola, exchange o routing key está escrito en las clases de
negocio:

| Servicio | Dónde |
|---|---|
| mq-admin | `TopologiaRabbit`, clase `@Configuration` única con exchanges, colas, DLQ y bindings |
| deliveries | `application.yml` → `agrotrack.rabbit.exchange-comandos` y `agrotrack.rabbit.comandos` (efecto → routing key) |
| notify | `application.yml` → `agrotrack.rabbit.colas` (`@RabbitListener(queues = "${...}")`) y `agrotrack.notify.reintentos` |
