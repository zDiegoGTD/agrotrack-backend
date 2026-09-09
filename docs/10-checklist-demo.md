# Checklist de la demostración (EP1)

Los 7 puntos que hay que mostrar, en el orden en que conviene contarlos, con
qué abrir en cada uno.

## Antes de empezar

1. Vocareum → **Start Lab** (verde) → **AWS**.
2. EC2 → las 3 instancias en **Running**. Si estaban detenidas, dales 3 min:
   Docker levanta todo solo.
3. Comprobar: `http://54.84.179.128` carga, y
   `http://54.84.179.128:8081/actuator/health` dice `UP`.

> Las IPs **públicas** de `ec2-kafka` y `ec2-mq` cambian cada vez que se
> detienen. No importa: los servicios se hablan por IP **privada**, que no
> cambia. Solo importa si necesitas entrar por SSH a esas dos.

---

## 5 · Tenant en IDaaS y usuarios registrados

**Portal de Azure → Microsoft Entra ID → Información general.**

Muestra: nombre del tenant (**Mish**), Id. de inquilino, y el contador de
**Usuarios**.

→ **Usuarios**: se ven las cuatro cuentas creadas para el caso.
→ **Aplicaciones empresariales → AgroTrack → Usuarios y grupos**: cada una con
su rol (`ADMIN`, `OPERADOR`, `CLIENTE`, `AUDITOR`).
→ **Registros de aplicaciones → AgroTrack → Roles de aplicación**: los cuatro
roles definidos, con su *Valor* en mayúsculas — que es lo que viaja en el
claim `roles` del token.

## 1 · Instancia de API Manager en funcionamiento

**API Gateway → APIs → `agrotrack-api`.**

Muestra el estado y la **Invoke URL**
(`https://85v8hc0ry6.execute-api.us-east-1.amazonaws.com`).

## 2 · Configuración que permite llamar al backend

En la misma API:

- **Routes**: `ANY /api/{proxy+}` (protegida) y `OPTIONS /api/{proxy+}` (sin
  autorización, para el preflight CORS del navegador).
- **Integrations**: HTTP proxy → `http://54.84.179.128:8081/{proxy}` — el BFF.
- **CORS**: origen `http://54.84.179.128`, cabecera `Authorization`.

Vale la pena decir en voz alta: **solo el BFF está expuesto**. Los puertos
8082–8088 de los servicios de dominio no se abren en el security group.

## 4 · El API Manager valida el JWT

**Sin token** — en cualquier terminal:

```bash
curl -i https://85v8hc0ry6.execute-api.us-east-1.amazonaws.com/api/me
```
→ `401 Unauthorized`. Lo rechaza el Gateway; la petición **no llega** al BFF.

**Con token inválido** (una firma cualquiera):

```bash
curl -i -H "Authorization: Bearer eyJhbGciOiJub25lIn0.e30.x" https://85v8hc0ry6.execute-api.us-east-1.amazonaws.com/api/me
```
→ `401`.

**Authorization → `azure-ad`**: enseña issuer
(`https://login.microsoftonline.com/74c11418-.../v2.0`) y las dos audiencias.

**Con token válido**: el punto 3, abajo.

## 6 · El frontend usa OAuth 2.0 / OIDC

Abre `http://54.84.179.128` → «Iniciar sesión con Microsoft».

Con las **DevTools abiertas en Network** antes de pulsar, se ve el flujo:

1. Redirección a `login.microsoftonline.com/.../oauth2/v2.0/authorize` con
   `response_type=code`, `code_challenge` (**PKCE**) y `scope=api://.../access_as_user`.
2. Login del usuario.
3. Vuelta a `http://54.84.179.128/#code=...`.
4. `POST .../oauth2/v2.0/token` → **access token**.

Pega el token en [jwt.ms](https://jwt.ms) y muestra los claims: `iss` (tu
tenant, v2.0), `aud` (el client id del API), `roles`, `oid`, `exp`.

## 3 · El frontend consume los endpoints a través del API Manager

Ya dentro de la aplicación, en **Network**: todas las llamadas van a
`85v8hc0ry6.execute-api.us-east-1.amazonaws.com/api/...`, **ninguna** a la IP
de la EC2. Cada una lleva `Authorization: Bearer ...` — lo pone el
**MsalInterceptor**, no código propio.

Y ahí se demuestra el "acepta las correctas" del punto 4: mismo endpoint que
antes daba 401, ahora responde 200.

**La autorización por rol se ve sola** si entras con distintos usuarios:

| Entras como | Qué ves |
|---|---|
| Productor (CLIENTE) | Solo sus entregas. Sin Reportería ni Auditoría en el menú |
| Jefe de acopio (OPERADOR) | Entregas por recibir y en clasificación; botones de cambio de estado |
| Auditor (AUDITOR) | Solo Auditoría. `GET /api/report/kpis` le da **403** |
| Admin (ADMIN) | KPIs, catálogo, todo |

Un momento que luce: entra como **Auditor** y pide reportería a mano →
`403 Forbidden` en `problem+json`. Eso es el indicador del 40% en vivo.

## 7 · Backend y frontend desplegados, activos e integrados

**En AWS**, EC2 → las 3 instancias, con sus tipos y la IP elástica.

**Por SSH**, en cada una:

```bash
ssh -i ~/.ssh/vockey.pem ec2-user@54.84.179.128 'docker ps'
```

- `ec2-apps`: Postgres + 8 servicios + frontend.
- `ec2-mq`: 2 nodos de RabbitMQ. `docker exec at-rabbit-1 rabbitmqctl cluster_status`
- `ec2-kafka`: 3 Zookeeper + 3 brokers + UI.

**Integrados de verdad** (esto es lo que separa "desplegado" de "funcionando"):
registra una entrega en el frontend y muéstrala llegar a los tres lados —

- **Auditoría** en la propia app: el timeline con los eventos en orden.
- **Kafka UI**: `http://<ip-pública-kafka>:8080` → tópico `deliveries.events`.
- **RabbitMQ**: `http://<ip-pública-mq>:15672` → las 6 colas, con consumidores.

## Prueba automática (opcional, y luce)

```powershell
cd C:\Users\deint\Desktop\AgroTrack\infra\aws
.\smoke-aws.ps1
```

Pide login real con código de dispositivo y recorre el flujo completo contra
el Gateway: 401 sin token, `/api/me`, catálogo, registrar, 409 al saltarse la
recepción, recibir → baja la capacidad, clasificar, despachar, timeline, KPIs,
topología. Todo en verde, en un minuto.

---

## Lo que hay que tener hecho en Azure antes

En **Registros de aplicaciones → AgroTrack**:

1. **Authentication** → *Single-page application* → Add URI:
   `http://54.84.179.128` (sin esto el login no puede volver a la app).
2. **Authentication** → *Allow public client flows* = **Sí** (solo si usarás
   `smoke-aws.ps1`).
3. **Manifiesto** → `requestedAccessTokenVersion` = `2`.

En **Entra ID → Usuarios**, crear cuatro y asignarles rol en *Aplicaciones
empresariales → AgroTrack → Usuarios y grupos*:

| Usuario | Rol |
|---|---|
| `admin@MishDomain.onmicrosoft.com` | ADMIN |
| `acopio@MishDomain.onmicrosoft.com` | OPERADOR |
| `productor@MishDomain.onmicrosoft.com` | CLIENTE |
| `auditor@MishDomain.onmicrosoft.com` | AUDITOR |
