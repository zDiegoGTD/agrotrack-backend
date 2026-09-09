# Despliegue en AWS (EC2 + Docker Compose)

Tres instancias, tres `compose.yml`, como pide la sección 7 del enunciado.

| EC2 | Compose | Tamaño sugerido | Contenido |
|---|---|---|---|
| `ec2-kafka` | `kafka/compose.yml` | t3.large (8 GB) | Zookeeper x3 + Kafka x3 + Kafka UI |
| `ec2-mq` | `mq/compose.yml` | t3.small (2 GB) | RabbitMQ x2 en clúster + Management |
| `ec2-apps` | `apps/compose.yml` | t3.large (8 GB) | PostgreSQL + 8 servicios + frontend |

> PostgreSQL va en `ec2-apps` (o en RDS si el crédito alcanza). Con Postgres
> son ~200 MB; no justifica una EC2 aparte.

## Orden de arranque

1. **ec2-kafka** → `docker compose --env-file ../.env.aws -f kafka/compose.yml up -d`
   (exporta `EC2_KAFKA_HOST=<ip privada>` antes: es lo que anuncian los brokers).
2. **ec2-mq** → `docker compose --env-file ../.env.aws -f mq/compose.yml up -d`.
3. **ec2-apps** → Postgres primero (`local/compose.yml` sirve, solo el servicio
   `postgres`), luego `apps/compose.yml up -d --build`. Los `depends_on` con
   healthcheck garantizan: mq-admin y kafka-admin declaran la topología antes
   de que deliveries, notify, audit y report intenten usarla.

## Security Groups (solo lo necesario)

| SG | Puerto | Origen | Para |
|---|---|---|---|
| sg-apps | 80, 8081 | 0.0.0.0/0 (o solo API Gateway) | frontend y BFF |
| sg-apps | 22 | tu IP | SSH |
| sg-mq | 5672 | sg-apps | AMQP desde los servicios |
| sg-mq | 15672 | tu IP | Management UI |
| sg-kafka | 9092-9094 | sg-apps | brokers desde los servicios |
| sg-kafka | 8080 | tu IP | Kafka UI |
| sg-apps | 5432 | sg-apps (interno) | Postgres, si está en la misma EC2 no hace falta abrirlo |

Los puertos 8082–8088 de los servicios de dominio **no se abren**: solo el BFF
habla con el exterior (flujo del enunciado: JWT → API Gateway → BFF → servicio).

## API Gateway (HTTP API) con JWT Authorizer

- Integración: `HTTP proxy` → `http://<ip pública ec2-apps>:8081/{proxy}`, ruta `ANY /api/{proxy+}`.
- Authorizer JWT: issuer `https://login.microsoftonline.com/<TENANT_ID>/v2.0`,
  audience `api://<API_CLIENT_ID>`.
- CORS en el Gateway: origen del frontend, cabeceras `Authorization, Content-Type`.
- El Gateway valida el token **antes** de que llegue al BFF; el BFF lo vuelve a
  validar y aplica la matriz de roles. Doble validación a propósito.

## Azure AD: App Registration "AgroTrack"

1. Entra ID → App registrations → New: SPA, redirect `http://<frontend>`.
2. Expose an API → Application ID URI `api://<CLIENT_ID>` → scope `access_as_user`.
3. App roles: `ADMIN`, `OPERADOR`, `CLIENTE`, `AUDITOR` (allowed member: Users).
4. Enterprise applications → AgroTrack → Users and groups → asignar roles.
5. Completar `frontend-agrotrack/src/environments/environment.prod.ts` y `.env.aws`.

Con un tenant propio (no el del instituto) todo esto se puede hacer sin pedir
permisos a nadie: `docs/04-checklist-entorno.md`, paso 3.

## AWS Academy

Las sesiones expiran (~4 h) y apagan las instancias. Todo está pensado para
eso: un `docker compose up -d` por EC2 y la topología de Rabbit/Kafka vuelve
sola (mq-admin y kafka-admin la declaran desde código). Asigna Elastic IPs o
actualiza `.env.aws` con las IPs privadas nuevas si cambian.
