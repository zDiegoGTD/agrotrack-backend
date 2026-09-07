# Contrato de eventos

Un solo formato de mensaje para todo lo que viaja por RabbitMQ y por Kafka.
Se define una vez, aquí, y no se negocia por servicio.

## Envelope

El enunciado exige `type`, `eventId`, `timestamp`, `traceId` y
`correlationId`. Se agregan tres campos más, con justificación:

```json
{
  "specVersion": "1.0",
  "type": "delivery.received",
  "eventId": "018f3c9a-6b1e-7a4e-9c2f-4d5e6f708192",
  "occurredAt": "2026-09-07T14:32:10.482Z",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "correlationId": "018f3c9a-0000-7000-8000-000000000001",
  "source": "ms-agrotrack-deliveries",
  "subject": "DEL-2026-000148",
  "actor": {
    "userId": "9f1c...azure-oid",
    "role": "OPERADOR"
  },
  "data": { }
}
```

| Campo | Por qué está |
|---|---|
| `specVersion` | El día que cambie el formato, los consumidores viejos necesitan saberlo. Cuesta un campo ahora y ahorra una migración después. |
| `type` | Qué pasó, en pasado. Ver catálogo abajo. |
| `eventId` | **UUID v7.** Es la clave de idempotencia: un consumidor que ya vio este `eventId` descarta el mensaje. v7 en vez de v4 porque es ordenable por tiempo, lo que ayuda en los índices de Oracle. |
| `occurredAt` | Cuándo pasó **en el dominio**, no cuándo se publicó. ISO-8601 en UTC. |
| `traceId` | Un request HTTP del usuario → N mensajes. Une todo el árbol. Formato W3C Trace Context, para que sirva si algún día se enchufa un tracer. |
| `correlationId` | Identifica el flujo de negocio (la entrega y su ciclo completo), no el request técnico. Es lo que permite reconstruir el timeline de auditoría. |
| `source` | Qué servicio lo emitió. Sin esto, depurar un DLQ es adivinar. |
| `subject` | Sobre qué entidad es. Es también la **clave de partición en Kafka**: garantiza que todos los eventos de una misma entrega caigan en la misma partición y se procesen **en orden**. Crítico para la auditoría. |
| `actor` | Quién lo hizo. El enunciado pide auditar "quién registró, recibió, clasificó o despachó". Sin esto, no se puede. |
| `data` | Payload específico del tipo. |

## Catálogo de tipos

Nombre en minúsculas, `dominio.hecho`, **en pasado**. Un evento es algo que
ya ocurrió; si el nombre está en imperativo, es un comando y va por RabbitMQ.

| `type` | Se emite en | `data` contiene |
|---|---|---|
| `delivery.registered` | T1 | productorId, productoId, cantidad, bodegaId |
| `delivery.received` | T2 | pesoRecibido, capacidadRestante |
| `delivery.classifying` | T3 | calidad, observaciones |
| `delivery.dispatching` | T4 | destino, transportista |
| `delivery.dispatched` | T5 | guiaNumero, fechaSalida |
| `delivery.rejected` | T6, T7, T8 | motivo, estadoPrevio |
| `catalog.capacity_changed` | catalog | bodegaId, anterior, actual |

## La regla que separa RabbitMQ de Kafka

Es la decisión conceptual que el caso está evaluando, así que queda escrita:

> **Kafka lleva hechos. RabbitMQ lleva comandos.**

- Un **hecho** ya ocurrió, es inmutable, y le puede interesar a cero o a
  muchos consumidores que aún no existen. Nadie le "responde" a un hecho.
  → `deliveries.events` en Kafka. Lo consumen `audit` y `report`, cada uno
  con su propio consumer group, sin enterarse el uno del otro.
- Un **comando** es una orden dirigida a **un** ejecutor, tiene que
  ejecutarse exactamente una vez, y si falla hay que reintentar y
  eventualmente mandarlo a una DLQ.
  → `q.cmd.email`, `q.cmd.receipt`, `q.cmd.voucher` en RabbitMQ.

Consecuencia práctica: `deliveries` publica **primero el hecho en Kafka** y
**después los comandos en RabbitMQ**. Si mañana hay que agregar SMS, se
agrega una cola nueva; el hecho en Kafka no cambia.

## Idempotencia

Todo consumidor, sin excepción:

1. Lee `eventId`.
2. Intenta insertarlo en su tabla `processed_events (event_id PK, processed_at)`.
3. Si la inserción choca con la PK → ya lo procesó → **ACK y descartar**.
4. Si no → procesa dentro de la misma transacción → ACK.

En RabbitMQ, ACK/NACK **explícitos** (`spring.rabbitmq.listener.simple.acknowledge-mode=manual`).
Nunca auto-ack: con auto-ack, un servicio que muere a mitad de proceso pierde
el mensaje sin dejar rastro.

## Reintentos y DLQ

- 3 reintentos con backoff exponencial (1s, 4s, 16s).
- Al cuarto fallo, a la DLQ correspondiente, **conservando el envelope
  original** y agregando `x-death` con el motivo.
- Métrica a exponer por Actuator: tasa de mensajes en DLQ. El enunciado la
  pide explícitamente y es la señal de que algo se rompió en silencio.
