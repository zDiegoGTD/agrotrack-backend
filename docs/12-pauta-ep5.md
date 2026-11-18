# Pauta EP5 (encargo Kafka): dónde se cumple cada indicador

Repositorios entregados:

- Backend: <https://github.com/zDiegoGTD/agrotrack-backend>
- Frontend: <https://github.com/zDiegoGTD/frontend-agrotrack>

Kafka lleva **hechos** (`deliveries.events`) y su derivado (`audit.timeline`);
RabbitMQ sigue llevando **comandos** sin cambios (ver
[`11-pauta-ep3-ep4.md`](11-pauta-ep3-ep4.md)). El detalle técnico está en
[`03-topologia-mensajeria.md`](03-topologia-mensajeria.md), sección Kafka.

## Indicadores de evaluación

| # | Indicador | % | Dónde se cumple |
|---|---|---|---|
| 1 | Configuración completa del cliente Kafka: brokers de Docker Compose, serializers, consumer groups y propiedades avanzadas | 12% | `spring.kafka` en el `application.yml` de [deliveries](../ms-agrotrack-deliveries/src/main/resources/application.yml), [audit](../ms-agrotrack-audit/src/main/resources/application.yml) y [report](../ms-agrotrack-report/src/main/resources/application.yml): `bootstrap-servers` (`KAFKA_BOOTSTRAP` apunta a los 3 brokers de [`infra/kafka/compose.yml`](../infra/kafka/compose.yml)), `group-id`, `acks=all`, `enable.idempotence`, `compression-type: lz4`, `delivery.timeout.ms`, `isolation-level: read-committed`, `max-poll-records`, `session.timeout.ms`, `concurrency`. Los (de)serializadores JSON en `config/KafkaConfig` de [deliveries](../ms-agrotrack-deliveries/src/main/java/cl/agrotrack/deliveries/config/KafkaConfig.java), [audit](../ms-agrotrack-audit/src/main/java/cl/agrotrack/audit/config/KafkaConfig.java) y [report](../ms-agrotrack-report/src/main/java/cl/agrotrack/report/config/KafkaConfig.java) |
| 2 | Productores y consumidores con **los dos** tópicos, con DTO | 12% | `deliveries.events`: produce deliveries ([`PublicadorMensajeria`](../ms-agrotrack-deliveries/src/main/java/cl/agrotrack/deliveries/infraestructura/mensajeria/PublicadorMensajeria.java), DTO `Envelope`), consumen audit y report. `audit.timeline`: produce audit ([`TimelineListener`](../ms-agrotrack-audit/src/main/java/cl/agrotrack/audit/infraestructura/mensajeria/TimelineListener.java), DTO [`ResumenTimeline`](../ms-agrotrack-audit/src/main/java/cl/agrotrack/audit/aplicacion/ResumenTimeline.java)), consume report ([`TimelineListener`](../ms-agrotrack-report/src/main/java/cl/agrotrack/report/infraestructura/mensajeria/TimelineListener.java)) |
| 3 | El administrador crea, lista y mantiene tópicos y configuraciones | 6% | [`AdministracionController`](../ms-agrotrack-kafka-admin/src/main/java/cl/agrotrack/kafkaadmin/infraestructura/web/AdministracionController.java) → [`KafkaAdminService`](../ms-agrotrack-kafka-admin/src/main/java/cl/agrotrack/kafkaadmin/aplicacion/KafkaAdminService.java): crear, listar, cambiar configuración, aumentar particiones, eliminar. Validación en [`AdminDtos`](../ms-agrotrack-kafka-admin/src/main/java/cl/agrotrack/kafkaadmin/infraestructura/web/dto/AdminDtos.java); tópicos del sistema protegidos (403) |
| 4 | Productores con DTO bien estructurados y serializers adecuados | 14% | `KafkaTemplate<String, Envelope>` en deliveries y `KafkaTemplate<String, Object>` con `ResumenTimeline` en audit; clave `StringSerializer` (código de la entrega, para el orden por partición), valor `JsonSerializer` con fechas ISO-8601 y sin cabecera de tipo |
| 5 | Consumidores con `@KafkaListener` y lógica separada de la mensajería | 14% | Los listeners solo reciben el DTO y delegan: audit `TimelineListener` → [`AuditService`](../ms-agrotrack-audit/src/main/java/cl/agrotrack/audit/aplicacion/AuditService.java); report [`KpiListener`](../ms-agrotrack-report/src/main/java/cl/agrotrack/report/infraestructura/mensajeria/KpiListener.java) → [`KpiService`](../ms-agrotrack-report/src/main/java/cl/agrotrack/report/aplicacion/KpiService.java) y `TimelineListener` → [`ActividadService`](../ms-agrotrack-report/src/main/java/cl/agrotrack/report/aplicacion/ActividadService.java) |
| 6 | Manejo de errores: validación de payloads, logging y reintentos | 12% | `ErrorHandlingDeserializer` (un cuerpo ilegible no traba el consumidor), validación del DTO en cada servicio (`MensajeInvalidoException`, sin reintento), `DefaultErrorHandler` con 3 intentos y `DeadLetterPublishingRecoverer` a `<tópico>.DLT` con cabeceras de causa y log `[dlt]`; idempotencia por `processed_events`. Probado en `TimelineListenerIT` y `KpiListenerIT` |
| 7 | Parámetros de tópicos: retención, cleanup policy, particiones y réplicas por flujo | 12% | [`TopologiaKafka`](../ms-agrotrack-kafka-admin/src/main/java/cl/agrotrack/kafkaadmin/config/TopologiaKafka.java): `deliveries.events` delete/7 d, `audit.timeline` compact,delete/30 d, las DLT delete/14 d; 3 particiones; 3 réplicas en AWS con `min.insync.replicas=2` |
| 8 | Consumer groups para balancear carga y procesar con resiliencia | 12% | `audit-consumer` y `report-consumer` leen `deliveries.events` completo cada uno; `report-timeline` lee `audit.timeline`. Cada grupo con 3 hilos (uno por partición): con más instancias Kafka reparte las particiones y, si una cae, otra retoma desde el último offset. Probado en `KpiListenerIT.concurrenciaIgualAParticiones` |
| 9 | Admin o scripts para crear, actualizar o inspeccionar tópicos **y consumer groups** | 6% | `GET /api/kafka/consumer-groups` (estado, miembros, lag por tópico), `POST .../offsets` (reprocesar o saltar, solo con el grupo detenido). Script replicable [`infra/aws/probar-kafka-admin.ps1`](../infra/aws/probar-kafka-admin.ps1) (15 casos) |

## Instrucciones del encargo

| La pauta pide | Dónde |
|---|---|
| Productores y consumidores conectados a tópicos Kafka | Indicadores 2, 4 y 5 |
| Administrador de Kafka: crear, modificar y eliminar tópicos y configuraciones | Indicador 3 |
| docker-compose de Kafka con Zookeeper y brokers | [`infra/kafka/compose.yml`](../infra/kafka/compose.yml): 3 Zookeeper + 3 brokers (`cp-kafka 7.6.1`) |
| docker-compose de Kafka UI para administrar brokers y tópicos | El mismo archivo, servicio `kafka-ui` (`provectuslabs/kafka-ui:v0.7.2`, puerto 8080) |
| Kafka complementa la arquitectura sin afectar RabbitMQ | RabbitMQ no cambió: los mismos comandos, colas, DLQ y `mq-admin`. Kafka y RabbitMQ salen del mismo `PublicadorMensajeria`, primero el hecho y después los comandos |
| Backend que compila, con pruebas | Tests con Kafka, PostgreSQL y RabbitMQ reales (Testcontainers): kafka-admin 33, deliveries 61, audit 15, report 23 |
