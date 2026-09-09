# AgroTrack — backend

Plataforma de acopio y despacho de producción agrícola. Caso semestral de
**Desarrollo Cloud Native I (DSY1107)**.

El frontend Angular vive en su propio repositorio: **agrotrack-frontend**.

## Qué hay aquí

| Carpeta | Qué es |
|---|---|
| `ms-agrotrack-bff` | BFF detrás del API Gateway. Valida el JWT de Azure AD y autoriza por rol |
| `ms-agrotrack-deliveries` | Entregas y máquina de estados. Publica hechos en Kafka y comandos en RabbitMQ |
| `ms-agrotrack-catalog` | Productos, bodegas y capacidad |
| `ms-agrotrack-notify` | Consume RabbitMQ: email, ticket de recepción y guía PDF |
| `ms-agrotrack-audit` | Consume Kafka: timeline inmutable de eventos |
| `ms-agrotrack-report` | Consume Kafka: KPIs y tiempo de ciclo |
| `ms-agrotrack-mq-admin` | Declara la topología de RabbitMQ desde código |
| `ms-agrotrack-kafka-admin` | Declara los tópicos de Kafka desde código |
| `infra` | Docker Compose (local y por instancia EC2) y scripts de AWS |
| `docs` | Decisiones de arquitectura, contratos y guías |

Cada microservicio es independiente: su propio `pom.xml`, su imagen Docker y
su base de datos. Comparten repositorio, no código.

## Arrancar en local

```bash
cd infra
cp .env.example .env
docker compose --env-file .env -f local/compose.yml up -d   # Postgres, RabbitMQ, Kafka
cd .. && for s in ms-agrotrack-*; do (cd $s && ./mvnw -q -DskipTests package); done
powershell -File infra/local/smoke.ps1 -KeepRunning
```

El smoke test arranca los 8 servicios y recorre el flujo completo del caso.

## Documentación

Empieza por [`docs/00-decisiones.md`](docs/00-decisiones.md) — qué se decidió y
por qué. Después:

- [`docs/01-maquina-de-estados.md`](docs/01-maquina-de-estados.md) — el corazón del caso
- [`docs/02-contrato-de-eventos.md`](docs/02-contrato-de-eventos.md) — Kafka lleva hechos, RabbitMQ comandos
- [`docs/07-azure-app-registration.md`](docs/07-azure-app-registration.md) — identidad
- [`docs/08-aws-academy.md`](docs/08-aws-academy.md) — despliegue
- [`docs/10-checklist-demo.md`](docs/10-checklist-demo.md) — guion de la demostración

## Tecnologías

Java 21 · Spring Boot 3.5.16 · PostgreSQL + Flyway · RabbitMQ · Kafka ·
Docker Compose · Azure AD (MSAL / OAuth 2.0 + OIDC) · AWS EC2 + API Gateway
