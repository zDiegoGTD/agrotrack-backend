# infra — AgroTrack

Tres stacks separados, uno por instancia EC2, tal como pide el enunciado (sección 7).

| Stack | Archivo | Dónde vive | Contenido |
|---|---|---|---|
| apps | `apps/compose.yml` | `ec2-apps` | bff, deliveries, catalog, notify, report, audit |
| mq | `mq/compose.yml` | `ec2-mq` | RabbitMQ, clúster de 2 nodos + Management UI |
| kafka | `kafka/compose.yml` | `ec2-kafka` | Zookeeper x3 + Kafka x3 + Kafka UI |

## Perfil local (`local/compose.yml`)

La topología completa **no cabe** en un PC de 16 GB: son ~14 contenedores y
Oracle solo ya pide 2 GB. `local/compose.yml` es la versión mínima para
desarrollar en el notebook — 1 Oracle, 1 RabbitMQ, 1 Kafka en modo KRaft
(sin Zookeeper) — funcionalmente equivalente para escribir código.

La topología con réplicas se prueba en AWS, no en el PC.

## Uso

```bash
cp .env.example .env      # y completa los valores
docker compose -f local/compose.yml up -d
```
