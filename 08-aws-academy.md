# Despliegue en AWS Academy (Learner Lab)

Complementa `infra/aws/README.md` con lo específico del Learner Lab.

## Lo que el Learner Lab impone

| Restricción | Consecuencia | Cómo se maneja |
|---|---|---|
| Sesión de ~4 h; al terminar, las EC2 se **detienen** | Se pierde lo que corría en memoria; los discos quedan | Todo arranca con `docker compose up -d` y `restart: unless-stopped`; los volúmenes persisten |
| Al reiniciar cambia la **IP pública** | El API Gateway y el frontend apuntan a una IP muerta | **Elastic IP** en `ec2-apps` (el lab permite hasta 5). Para mq y kafka se usan **IPs privadas**, que no cambian |
| Solo región **us-east-1** | — | Todo en N. Virginia |
| Tipos de instancia limitados (`t2/t3` hasta `large`, ~32 vCPU en total) | Sin máquinas grandes | `t3.large` para apps y kafka, `t3.small` para mq |
| Sin IAM users; rol fijo `LabRole` | No se pueden crear credenciales para CI | Se despliega copiando el código por SSH desde el PC (`infra/aws/subir.ps1`) |
| Llave SSH `vockey` ya creada | — | Se descarga desde **AWS Details → Download PEM** en Vocareum |
| Crédito limitado (~$50–100) | Cada hora con 3 instancias grandes cuesta ~$0.25 | **Detener** las instancias al terminar cada sesión de trabajo |

## Paso a paso

### 1. Arrancar el lab y descargar la llave

Vocareum → **Start Lab** → cuando el círculo esté verde, **AWS Details → Download
PEM**. Guardarla en `C:\Users\deint\.ssh\vockey.pem`. Después **AWS** (botón) para
entrar a la consola.

### 2. Security groups (VPC → Security Groups → Create)

Crear en este orden (los de atrás referencian a los de adelante):

**`agrotrack-apps`**

| Tipo | Puerto | Origen | Para |
|---|---|---|---|
| SSH | 22 | 0.0.0.0/0 | administrar (la IP publica del PC cambio a mitad del primer despliegue y corto el SSH; la llave vockey sigue siendo obligatoria) |
| HTTP | 80 | 0.0.0.0/0 | frontend |
| Custom TCP | 8081 | 0.0.0.0/0 | BFF (el API Gateway entra por aquí) |

**`agrotrack-mq`**

| Tipo | Puerto | Origen |
|---|---|---|
| SSH | 22 | My IP |
| Custom TCP | 5672 | `agrotrack-apps` |
| Custom TCP | 15672 | My IP |
| Custom TCP | 4369, 25672 | `agrotrack-mq` (clúster entre nodos) |

**`agrotrack-kafka`**

| Tipo | Puerto | Origen |
|---|---|---|
| SSH | 22 | My IP |
| Custom TCP | 9092–9094 | `agrotrack-apps` |
| Custom TCP | 8080 | My IP (Kafka UI) |

Los puertos 8082–8088 **no se abren**: solo el BFF habla con el exterior.

### 3. Tres instancias (EC2 → Launch instance)

| Nombre | AMI | Tipo | SG | Disco | User data |
|---|---|---|---|---|---|
| `ec2-kafka` | Amazon Linux 2023 | t3.large | agrotrack-kafka | 20 GB | `infra/aws/user-data.sh` |
| `ec2-mq` | Amazon Linux 2023 | t3.small | agrotrack-mq | 10 GB | `infra/aws/user-data.sh` |
| `ec2-apps` | Amazon Linux 2023 | t3.large | agrotrack-apps | 30 GB | `infra/aws/user-data.sh` |

Key pair: **vockey**. El *user data* (pestaña *Advanced details*) instala Docker
y Compose; con eso la instancia queda lista para recibir el código.

**Elastic IP** para `ec2-apps`: EC2 → Elastic IPs → Allocate → Associate.
Anotar las **IPs privadas** de las tres (columna *Private IPv4*).

### 4. Subir el código y levantar cada stack

Desde el PC, en PowerShell (necesita la llave y las IPs):

```powershell
cd C:\Users\deint\Desktop\AgroTrack\infra\aws

# Kafka primero (los brokers anuncian su IP privada)
.\subir.ps1 -Ip <IP_PUBLICA_KAFKA> -Stack kafka -Env @{ EC2_KAFKA_HOST = '<IP_PRIVADA_KAFKA>' }

# RabbitMQ
.\subir.ps1 -Ip <IP_PUBLICA_MQ> -Stack mq

# Apps: Postgres + 8 servicios + frontend (compila las imagenes en la EC2: 10-15 min la primera vez)
.\subir.ps1 -Ip <IP_ELASTICA_APPS> -Stack apps
```

`subir.ps1` copia los repos por `scp` (sin `node_modules` ni `target`), sube tu
`.env.aws` y ejecuta `docker compose up -d --build` en la instancia. Antes de la
tercera llamada, completa `infra/.env.aws` (copiar de `.env.aws.example`) con:
`POSTGRES_HOST=postgres`, `RABBITMQ_HOST=<IP_PRIVADA_MQ>`,
`KAFKA_BOOTSTRAP=<IP_PRIVADA_KAFKA>:9092,...:9093,...:9094`, los IDs de Azure y
`CORS_ORIGENES=http://<IP_ELASTICA_APPS>`.

Verificar: `http://<IP_ELASTICA_APPS>:8081/actuator/health` → `{"status":"UP"}`.

### 5. API Gateway (HTTP API) con JWT Authorizer

API Gateway → **Create API → HTTP API → Build**:

1. **Integrations**: HTTP, `ANY`, URL `http://<IP_ELASTICA_APPS>:8081/{proxy}`.
2. **Routes**: `ANY /api/{proxy+}` → esa integración.
3. **Authorization** → Create → **JWT**:
   - Identity source: `$request.header.Authorization`
   - Issuer URL: `https://login.microsoftonline.com/<TENANT_ID>/v2.0`
   - Audience: `<CLIENT_ID>` **y** `api://<CLIENT_ID>` (dos entradas)
   - Attach a la ruta `ANY /api/{proxy+}`.
4. **CORS**: origins `http://<IP_ELASTICA_APPS>`, headers `Authorization, Content-Type`, methods `GET, POST, PUT, OPTIONS`.
5. Stage `$default` con auto-deploy. Anotar la **Invoke URL**
   (`https://xxxx.execute-api.us-east-1.amazonaws.com`).

Prueba: `curl https://xxxx.execute-api.us-east-1.amazonaws.com/api/me` → 401 sin
token (el Gateway lo rechaza antes de llegar al BFF), 200 con un token de Azure.

### 6. Frontend apuntando al Gateway

En `frontend-agrotrack/src/environments/environment.prod.ts`: `apiUrl` = Invoke
URL, `redirectUri` = `http://<IP_ELASTICA_APPS>`, IDs de Azure. Agregar esa
`redirectUri` en Azure (Authentication → SPA). Volver a subir el stack apps
(`.\subir.ps1 -Ip <IP_ELASTICA_APPS> -Stack apps`).

Abrir `http://<IP_ELASTICA_APPS>` → «Iniciar sesión con Microsoft» → dashboard.

## Cada vez que se acaba la sesión

1. Start Lab → esperar verde.
2. EC2 → seleccionar las tres → **Instance state → Start**. La Elastic IP se
   conserva; las privadas también. Docker levanta todo solo (`restart:
   unless-stopped`); dar 2–3 minutos.
3. Si por alguna razón cambió una IP privada, editar `.env.aws` y repetir
   `subir.ps1 -Stack apps`.

**Al terminar de trabajar: Instance state → Stop.** Detenida no consume crédito
de cómputo.

## Lo que quedó creado (2026-09-07)

Todo lo anterior se ejecutó con `infra/aws/crear.ps1`, `subir.ps1` y `gateway.ps1`
(no a mano). Valores vivos en `infra/.aws-hosts.json` y `infra/.env.aws`.

| Recurso | Valor |
|---|---|
| Cuenta / región | 902971831665 / us-east-1 (subnet en us-east-1a) |
| ec2-kafka | t3.large · privada 172.31.4.207 · 3 ZK + 3 brokers + UI |
| ec2-mq | t3.small · privada 172.31.14.198 · RabbitMQ 2 nodos |
| ec2-apps | t3.large · **Elastic IP 54.84.179.128** · Postgres + 8 servicios + frontend |
| API Gateway | `agrotrack-api` → `https://85v8hc0ry6.execute-api.us-east-1.amazonaws.com` con JWT Authorizer (issuer del tenant Mish, audiences `api://<client>` y `<client>`) |
| Frontend | `http://54.84.179.128` (nginx) → llama al Gateway |

Tropiezos reales durante el despliegue, por si se repiten:

- La IP pública del PC cambió a mitad del despliegue y cortó el SSH (las reglas
  eran `/32`). Por eso el 22 quedó abierto a cualquier origen: la llave sigue
  siendo obligatoria.
- `us-east-1e` no ofrece `t3.large`; se fija `us-east-1a`.
- Los nombres de security group no pueden empezar por `sg-`.
- `docker compose` se ejecuta con `nohup` en la instancia: si la sesión SSH se
  cae durante los 10–15 min de construcción, el despliegue sigue.
