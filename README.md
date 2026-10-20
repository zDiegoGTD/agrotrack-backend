# AgroTrack — backend

[![Seguridad](https://sonarcloud.io/api/project_badges/measure?project=zDiegoGTD_agrotrack-backend&metric=security_rating)](https://sonarcloud.io/summary/overall?id=zDiegoGTD_agrotrack-backend) [![Confiabilidad](https://sonarcloud.io/api/project_badges/measure?project=zDiegoGTD_agrotrack-backend&metric=reliability_rating)](https://sonarcloud.io/summary/overall?id=zDiegoGTD_agrotrack-backend) [![Mantenibilidad](https://sonarcloud.io/api/project_badges/measure?project=zDiegoGTD_agrotrack-backend&metric=sqale_rating)](https://sonarcloud.io/summary/overall?id=zDiegoGTD_agrotrack-backend)

Plataforma de acopio y despacho de producción agrícola. Caso semestral de
**Desarrollo Cloud Native I (DSY1107)**.

El frontend Angular vive en su propio repositorio:
[**frontend-agrotrack**](https://github.com/zDiegoGTD/frontend-agrotrack).

## Qué hay aquí

| Carpeta | Qué es |
|---|---|
| `ms-agrotrack-bff` | BFF detrás del API Gateway. Valida el JWT de Azure AD y autoriza por rol |
| `ms-agrotrack-deliveries` | Entregas y máquina de estados. Publica hechos en Kafka y comandos en RabbitMQ |
| `ms-agrotrack-catalog` | Productos, bodegas y capacidad |
| `ms-agrotrack-notify` | Consume RabbitMQ con ACK manual: email, ticket de recepción y guía PDF; lo que falla va a la DLQ |
| `ms-agrotrack-audit` | Consume Kafka: timeline inmutable de eventos |
| `ms-agrotrack-report` | Consume Kafka: KPIs y tiempo de ciclo |
| `ms-agrotrack-users` | Cuentas: registro al primer ingreso, aprobación por el admin y ficha del productor |
| `ms-agrotrack-mq-admin` | Declara la topología de RabbitMQ desde código y expone una API REST para crear y eliminar colas, exchanges y bindings |
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

El smoke test arranca los 9 servicios y recorre el flujo completo del caso.

## Documentación

Empieza por [`docs/00-decisiones.md`](docs/00-decisiones.md) — qué se decidió y
por qué. Después:

- [`docs/01-maquina-de-estados.md`](docs/01-maquina-de-estados.md) — el corazón del caso
- [`docs/02-contrato-de-eventos.md`](docs/02-contrato-de-eventos.md) — Kafka lleva hechos, RabbitMQ comandos
- [`docs/03-topologia-mensajeria.md`](docs/03-topologia-mensajeria.md) — colas, DLQ, API de administración y confirmación de mensajes
- [`docs/07-azure-app-registration.md`](docs/07-azure-app-registration.md) — identidad
- [`docs/08-aws-academy.md`](docs/08-aws-academy.md) — despliegue
- [`docs/09-pauta-ep1.md`](docs/09-pauta-ep1.md) — pauta EP1 (identidad, API Manager) y dónde se cumple
- [`docs/11-pauta-ep3-ep4.md`](docs/11-pauta-ep3-ep4.md) — **pauta EP3 y EP4 (RabbitMQ) y dónde se cumple cada indicador**
- [`docs/10-checklist-demo.md`](docs/10-checklist-demo.md) — guion de la demostración

## Seguridad en una línea por capa

| Capa | Qué valida |
|---|---|
| Azure AD | Dos apps: **AgroTrack Web** inicia sesión y **AgroTrack API** define roles y scope. Solo usuarios con un rol asignado pueden entrar |
| API Gateway | Firma, emisor, audiencia, vigencia y scope `access_as_user`; agrega la cabecera que identifica al Gateway |
| BFF | Solo acepta tráfico del Gateway; vuelve a validar el token; matriz de roles; cuenta aprobada en `ms-agrotrack-users`; límite de peticiones |
| Cada microservicio | Valida el token otra vez y el rol en cada endpoint |

Para demostrarlo en vivo contra AWS: `infra/aws/probar-jwt.ps1`. La API de administración de RabbitMQ: `infra/aws/probar-mq-admin.ps1`.

## Calidad

Cada push a `main` compila los 9 servicios y los analiza en
[SonarQube Cloud](https://sonarcloud.io/summary/overall?id=zDiegoGTD_agrotrack-backend)
(`.github/workflows/sonarqube.yml`). Los tests corren en local con
`./mvnw test` en cada servicio; los de integración usan RabbitMQ, Kafka y
PostgreSQL reales con Testcontainers.

## Tecnologías

Java 21 · Spring Boot 3.5.16 · PostgreSQL + Flyway · RabbitMQ · Kafka ·
Docker Compose · Azure AD (MSAL / OAuth 2.0 + OIDC) · AWS EC2 + API Gateway
