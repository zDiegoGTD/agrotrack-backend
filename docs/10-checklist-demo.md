# Guion de la demostración (EP1)

Los 7 puntos de la pauta en el orden en que conviene contarlos, con qué abrir
en cada uno. Duración estimada: 15 minutos.

## Antes de empezar (10 min antes)

1. Vocareum → **Start Lab** (punto verde) → pegar las credenciales en
   `%USERPROFILE%\.aws\credentials`.
2. Encender y comprobar todo:
   ```powershell
   cd C:\Users\deint\Desktop\AgroTrack\infra\aws; .\encender.ps1
   ```
   Tiene que terminar con `BFF sano`, `aplicacion por HTTPS` y `API Gateway rechaza sin token`.
3. Dejar abiertas: el portal de Azure (Entra ID), la consola de AWS (API
   Gateway y EC2), una PowerShell en `infra\aws` y **ventanas de incógnito**
   para cada usuario.

> Las IPs públicas de `ec2-kafka` y `ec2-mq` cambian en cada arranque;
> `encender.ps1` las actualiza. Los servicios se hablan por IP privada.

---

## 5 · Tenant en IDaaS y usuarios registrados

**Portal de Azure → Microsoft Entra ID → Información general**: tenant
**Mish**, Id. de inquilino, contador de usuarios.

- **Usuarios**: las cuentas del caso.
- **Aplicaciones empresariales → AgroTrack → Usuarios y grupos**: cada cuenta
  con su **App Role** (`Administrador`, `Jefe de acopio`, `Productor`, `Auditor`).
- **Aplicaciones empresariales → AgroTrack → Propiedades → ¿Asignación
  requerida? = Sí**: solo entra quien tiene un rol asignado.
- **Registros de aplicaciones → AgroTrack**: *Roles de aplicación* (el valor
  en mayúsculas viaja en el claim `roles`) y *Exponer una API* (scope
  `access_as_user`).

## 1 · Instancia de API Manager en funcionamiento

**API Gateway → APIs → `agrotrack-api`**: estado y **Invoke URL**
`https://85v8hc0ry6.execute-api.us-east-1.amazonaws.com`.

## 2 · Configuración que permite llamar al backend

En la misma API:

- **Routes**: `ANY /api/{proxy+}` con el authorizer `azure-ad` y el scope
  `access_as_user`; `OPTIONS /api/{proxy+}` sin autorización (preflight CORS);
  `ANY /{proxy+}` y `GET /` sirven el frontend por HTTPS.
- **Authorizers → `azure-ad`**: issuer `https://login.microsoftonline.com/<tenant>/v2.0`
  y las dos audiencias (`api://<client-id>` y `<client-id>`).
- **Integrations**: HTTP proxy al BFF (`:8081/api/{proxy}`) con la cabecera
  `X-Origen-Gateway`, que hace del Gateway la única puerta al BFF.

## 4 · El API Manager valida el JWT: rechaza inválidas y acepta correctas

```powershell
.\probar-jwt.ps1
```

Pide login con código de dispositivo y muestra una tabla:

| Caso | Respuesta |
|---|---|
| Sin token | 401 |
| Texto que no es un JWT | 401 |
| Claims de Azure con firma inventada | 401 |
| **Token real de Azure** | **200** |
| Token real con el rol cambiado a mano | 401 |
| Token real directo a la EC2, sin Gateway | 403 `ORIGEN_NO_PERMITIDO` |

Lo que conviene decir: *el Gateway valida firma, emisor, audiencia, vigencia y
scope; el BFF lo vuelve a validar y además autoriza por rol. Y nadie puede
saltarse el Gateway yendo directo a la máquina.*

Para mostrar la validación del BFF en el código: `ValidacionJwtTest` (12 casos
con tokens firmados de verdad).

## 6 · El frontend usa OAuth 2.0 / OIDC

Incógnito → la Invoke URL → **Iniciar sesión con Microsoft**, con las
**DevTools en Network** abiertas antes de pulsar:

1. Redirección a `login.microsoftonline.com/.../oauth2/v2.0/authorize` con
   `response_type=code`, `code_challenge` (**PKCE**) y `scope=api://.../access_as_user`.
2. Login.
3. `POST .../oauth2/v2.0/token` → access token.

Ya dentro: **Mi sesión** muestra los claims del token (roles, scopes, emisor,
audiencia, vencimiento) y explica quién valida qué.

## 3 · El frontend consume los endpoints a través del API Manager

En **Network**: todas las llamadas van a `.../api/...` de la Invoke URL, cada
una con `Authorization: Bearer ...` — lo pone el **MsalInterceptor**.

En **Mi sesión → Probar todo**: llamadas reales a seis endpoints con la
respuesta esperada según el rol (200 o 403) y la recibida.

**La autorización por rol**, entrando con cada usuario:

| Entras como | Menú | Prueba de rechazo |
|---|---|---|
| Productor (CLIENTE) | Inicio, Entregas, Mi ficha | Mi sesión → reportería da 403 |
| Jefe de acopio (OPERADOR) | Inicio, Entregas, Catálogo | avanza estados; no crea productos |
| Auditor (AUDITOR) | Inicio, Auditoría | solo lectura |
| Admin (ADMIN) | todo, más Usuarios | — |

**La aprobación de cuentas** (segunda capa, además del rol):

1. Entra un usuario nuevo del tenant → **"Tu cuenta espera aprobación"**,
   aunque su token trae rol. En Network: `403` con `codigo: CUENTA_PENDIENTE`.
2. El admin, en **Usuarios → Pendientes**, pulsa **Aprobar**.
3. El usuario pulsa **Volver a comprobar** y entra.

*El rol lo pone Azure; si la cuenta puede usar el sistema lo decide AgroTrack.*

## 7 · Backend y frontend desplegados, activos e integrados

**EC2**: las 3 instancias y la IP elástica de apps. Por SSH:

```bash
ssh -i ~/.ssh/vockey.pem ec2-user@54.84.179.128 'docker ps --format "{{.Names}}: {{.Status}}"'
```

- `ec2-apps`: PostgreSQL + 9 microservicios + frontend, todos `healthy`.
- `ec2-mq`: RabbitMQ en clúster de 2 nodos.
- `ec2-kafka`: 3 ZooKeeper + 3 brokers (réplica 3) + Kafka UI.

**Integrados de verdad**: registrar una entrega como Productor, avanzarla como
Jefe de acopio y mostrarla llegar a todos lados:

- **Auditoría** (como Auditor): la línea de tiempo con cada evento, quién y cuándo.
- **Reportería** (como Admin): KPIs actualizados.
- **Base de datos**: la tabla `usuario` antes y después de aprobar a alguien:
  ```bash
  ssh -i ~/.ssh/vockey.pem ec2-user@54.84.179.128 -t "docker exec -it at-postgres psql -U agro_users -d agro_users -c 'SELECT nombre, estado, rol_ultimo_token, aprobado_por FROM usuario'"
  ```

## Prueba automática del flujo completo (opcional)

```powershell
.\smoke-aws.ps1
```

Con un usuario con varios roles recorre: 401 sin token, catálogo, registrar,
409 al saltarse la recepción, recibir (baja la capacidad), clasificar,
despachar, línea de tiempo y KPIs.

## Al terminar

```powershell
.\apagar.ps1
```
