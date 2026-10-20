# Pauta EP3 (encargo) y EP4 (presentación): dónde se cumple cada punto

Repositorios entregados:

- Backend: <https://github.com/zDiegoGTD/agrotrack-backend>
- Frontend: <https://github.com/zDiegoGTD/frontend-agrotrack>

La pauta de evaluación del EP3 y del EP4 evalúa los mismos 8 indicadores de
código de RabbitMQ; el EP4 suma lo que se muestra en vivo. El detalle técnico de
la mensajería está en [`03-topologia-mensajeria.md`](03-topologia-mensajeria.md).

## Indicadores de evaluación

| # | Indicador | % | Dónde se cumple |
|---|---|---|---|
| 1 | Nombres de colas, exchanges y bindings centralizados en `application.yml` o una clase `@Configuration` | 12% | **mq-admin:** clase única [`TopologiaRabbit`](../ms-agrotrack-mq-admin/src/main/java/cl/agrotrack/mqadmin/config/TopologiaRabbit.java). **deliveries:** `agrotrack.rabbit.exchange-comandos` y `agrotrack.rabbit.comandos` en [`application.yml`](../ms-agrotrack-deliveries/src/main/resources/application.yml) ([`PropiedadesRabbit`](../ms-agrotrack-deliveries/src/main/java/cl/agrotrack/deliveries/infraestructura/mensajeria/PropiedadesRabbit.java)). **notify:** `agrotrack.rabbit.colas` y `agrotrack.notify.reintentos` en [`application.yml`](../ms-agrotrack-notify/src/main/resources/application.yml) |
| 2 | Beans `Queue`, `Exchange` y `Binding` por caso de uso | 13% | [`TopologiaRabbit.topologia()`](../ms-agrotrack-mq-admin/src/main/java/cl/agrotrack/mqadmin/config/TopologiaRabbit.java): 3 flujos (email, ticket de recepción, guía PDF), cada uno con su cola, su DLQ y dos bindings (direct y topic), más el exchange de descartes |
| 3 | Configuración de mensajería separada de la lógica de negocio | 10% | Paquetes `config/` e `infraestructura/mensajeria/` aparte del dominio. El dominio de deliveries ([`MaquinaEstadosEntrega`](../ms-agrotrack-deliveries/src/main/java/cl/agrotrack/deliveries/dominio/MaquinaEstadosEntrega.java)) solo devuelve *efectos*; quién los convierte en mensajes es [`PublicadorMensajeria`](../ms-agrotrack-deliveries/src/main/java/cl/agrotrack/deliveries/infraestructura/mensajeria/PublicadorMensajeria.java) |
| 4 | Consumidores con `@RabbitListener`, agrupados por dominio funcional | 15% | notify: [`EmailListener`](../ms-agrotrack-notify/src/main/java/cl/agrotrack/notify/infraestructura/mensajeria/EmailListener.java) (notificaciones), [`TicketListener`](../ms-agrotrack-notify/src/main/java/cl/agrotrack/notify/infraestructura/mensajeria/TicketListener.java) (recepción en bodega), [`VoucherListener`](../ms-agrotrack-notify/src/main/java/cl/agrotrack/notify/infraestructura/mensajeria/VoucherListener.java) (despacho) |
| 5 | ACK y manejo explícito de errores | 20% | `acknowledge-mode: manual`. [`ProcesadorComandos`](../ms-agrotrack-notify/src/main/java/cl/agrotrack/notify/infraestructura/mensajeria/ProcesadorComandos.java): ACK al procesar o ante un duplicado; NACK sin requeue (DLQ) si el mensaje es inválido; reintentos acotados (1 s y 4 s) ante fallos transitorios y NACK a la DLQ al agotarlos; NACK con requeue si el servicio se apaga. Rutas documentadas en la clase y en `03-topologia-mensajeria.md` |
| 6 | El administrador expone endpoints REST bien definidos | 13% | [`AdministracionController`](../ms-agrotrack-mq-admin/src/main/java/cl/agrotrack/mqadmin/infraestructura/web/AdministracionController.java): `POST/DELETE /api/mq/queues`, `POST/DELETE /api/mq/exchanges`, `POST/DELETE /api/mq/bindings`, `DELETE /api/mq/queues/{nombre}/mensajes` (purgar), `GET /api/mq/topology` |
| 7 | Lógica de administración encapsulada en un servicio | 10% | [`RabbitAdminService`](../ms-agrotrack-mq-admin/src/main/java/cl/agrotrack/mqadmin/aplicacion/RabbitAdminService.java). Ningún controlador usa `AmqpAdmin` directamente |
| 8 | Validación de los parámetros de entrada | 7% | DTOs con Bean Validation en [`AdminDtos`](../ms-agrotrack-mq-admin/src/main/java/cl/agrotrack/mqadmin/infraestructura/web/dto/AdminDtos.java) (nombre obligatorio, máximo 200, caracteres permitidos, sin el prefijo reservado `amq.`, tipo de exchange válido). Errores 400 con `codigo: DATOS_INVALIDOS` y el motivo por campo ([`ApiExceptionHandler`](../ms-agrotrack-mq-admin/src/main/java/cl/agrotrack/mqadmin/infraestructura/web/ApiExceptionHandler.java)) |

## Instrucciones del encargo

| La pauta pide | Dónde |
|---|---|
| Productores y consumidores conectados a RabbitMQ | Productor: deliveries ([`PublicadorMensajeria`](../ms-agrotrack-deliveries/src/main/java/cl/agrotrack/deliveries/infraestructura/mensajeria/PublicadorMensajeria.java)). Consumidores: notify |
| Mensajes no entregados a DLQ y registrados en logs | Consumidor: NACK a la DLQ con log `[dlq]` y métrica `agrotrack.notify.dlq.total`. Productor: [`RegistroNoEntregados`](../ms-agrotrack-deliveries/src/main/java/cl/agrotrack/deliveries/infraestructura/mensajeria/RegistroNoEntregados.java) registra lo que el broker no enruta (`mandatory`) o rechaza (publisher confirms) |
| Administrador que crea y elimina colas, exchanges y bindings | `ms-agrotrack-mq-admin` (indicadores 6 a 8). Solo `ADMIN`; la topología del sistema está protegida (403 `RECURSO_DEL_SISTEMA`) |
| Backend que compila, con buenas prácticas y pruebas | Tests unitarios y de integración con RabbitMQ, Kafka y PostgreSQL reales (Testcontainers). Análisis en SonarQube Cloud: 0 problemas abiertos, nota A en seguridad, confiabilidad y mantenibilidad |
| Filtros que validan el JWT del IDaaS | API Gateway (JWT authorizer), BFF ([`SecurityConfig`](../ms-agrotrack-bff/src/main/java/cl/agrotrack/bff/config/SecurityConfig.java)) y cada microservicio. Ver [`09-pauta-ep1.md`](09-pauta-ep1.md) |
| Frontend con login IDaaS y JWT en las llamadas | [frontend-agrotrack](https://github.com/zDiegoGTD/frontend-agrotrack): MSAL (Authorization Code + PKCE) con la app **AgroTrack Web**; el `MsalInterceptor` agrega el Bearer |
| Base de datos | PostgreSQL 16, una base por servicio, con JPA y Flyway. Acordado con el docente: no se entregaron cuentas de base de datos cloud ([`00-decisiones.md`](00-decisiones.md), D1) |
| `.gitignore` | Secretos (`.env`, `.env.aws`, IPs, clave privada de desarrollo) fuera del repositorio |

## Presentación (EP4): qué se muestra en vivo

| # | Pide | Cómo se muestra |
|---|---|---|
| 1 | API Manager en la nube | AWS → API Gateway `agrotrack-api` |
| 2 | Configuración del API Manager | Rutas, authorizer JWT `azure-ad`, integración al BFF |
| 3 | Frontend consume a través del API Manager | DevTools → Network: llamadas a la URL del Gateway con `Authorization: Bearer` |
| 4 | El API Manager valida el JWT | [`infra/aws/probar-jwt.ps1`](../infra/aws/probar-jwt.ps1): 401 sin token, con basura, firma inventada o token alterado; 200 con el token real; 403 directo a la EC2 |
| 5 | Tenant IDaaS con usuarios | Entra ID, tenant Mish: apps **AgroTrack API** (roles, scope, asignación requerida) y **AgroTrack Web** (login). Ver [`07-azure-app-registration.md`](07-azure-app-registration.md) |
| 6 | Login OAuth 2.0 / OIDC con JWT válido | Login con MSAL; pantalla *Mi sesión* con los claims del token |
| 7 | Backend y frontend desplegados e integrados | 3 EC2 (apps, Kafka, RabbitMQ) con Docker Compose |
| 8 | Dos nodos RabbitMQ en clúster | `rabbitmqctl cluster_status` en `ec2-mq` ([`infra/mq/compose.yml`](../infra/mq/compose.yml)) |
| 9 | Tres colas con sus DLQ | Management UI → Queues: `q.cmd.email`, `q.cmd.receipt`, `q.cmd.voucher` y sus `.dlq` |
| 10 | Exchanges direct y topic | Management UI → Exchanges: `cmd.direct`, `cmd.topic`, `cmd.dead.dlx` |
| 11 | docker-compose de RabbitMQ en la nube | `docker compose ps` del stack `mq` en `ec2-mq` |

La administración por API se demuestra con [`infra/aws/probar-mq-admin.ps1`](../infra/aws/probar-mq-admin.ps1): crea y elimina cola, DLQ, exchange y binding, y comprueba las protecciones (403, 400, 404 y 401).
