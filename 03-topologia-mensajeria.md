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

**Pendiente de confirmar el martes:** si la pauta define estos servicios de
otra forma, esta interpretación se descarta sin costo — todavía no hay código.
