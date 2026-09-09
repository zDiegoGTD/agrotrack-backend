# Azure AD (Entra ID): App Registration "AgroTrack"

Lo que pide la sección 4 del enunciado: `clientId`, `redirectUri`,
`authority = https://login.microsoftonline.com/<TENANT_ID>/`, MSAL en Angular,
JWT Authorizer en API Gateway y validación en Spring Security.

## 0. ¿Puedo crear apps en este tenant?

`portal.azure.com` → **Microsoft Entra ID** → **App registrations** → **+ New registration**.

- **Se abre el formulario** → sigue en el paso 1 con el tenant Duoc.
- **"You do not have permission"** o el botón no aparece → el instituto lo
  bloqueó. Plan B, en este orden:
  1. **Preguntar al profesor** qué tenant usa el curso. Si la asignatura exige
     Azure AD, tiene que haber una vía prevista (un tenant del curso, o que cada
     uno cree el suyo).
  2. **Tenant propio**: `portal.azure.com` con una cuenta Microsoft personal →
     crear cuenta gratuita de Azure (pide tarjeta solo para verificar; no cobra)
     → Entra ID → **Manage tenants** → **Create**. Ahí eres Global Admin.

Todo lo de abajo es idéntico en cualquier tenant.

## 1. Registrar la aplicación

**App registrations → New registration**

| Campo | Valor |
|---|---|
| Name | `AgroTrack` |
| Supported account types | *Accounts in this organizational directory only* (single tenant) |
| Redirect URI | plataforma **Single-page application (SPA)**, URI `http://localhost:4200` |

Al crear, anota de la pantalla **Overview**:

- **Application (client) ID** → `CLIENT_ID`
- **Directory (tenant) ID** → `TENANT_ID`

> Se usa **una sola app** para el frontend y el API. El enunciado habla de
> `<API_CLIENT_ID>`: aquí es el mismo `CLIENT_ID`. Funciona igual y es la mitad
> de configuración. Si la pauta exige dos apps separadas, se registra una
> segunda "AgroTrack API" y se repiten los pasos 3 y 4 en ella.

## 2. Autenticación

**Authentication** → en la plataforma SPA, agregar también la URI del frontend
en AWS cuando exista (`http://<IP_PUBLICA_EC2_APPS>`). Dejar *Implicit grant*
**desmarcado** (MSAL usa PKCE).

## 3. Exponer el API (scope)

**Expose an API** → **Add** junto a *Application ID URI* → aceptar el valor
propuesto `api://<CLIENT_ID>` → **Save**.

**+ Add a scope**:

| Campo | Valor |
|---|---|
| Scope name | `access_as_user` |
| Who can consent | Admins and users |
| Admin consent display name | Acceder a AgroTrack como el usuario |
| Admin consent description | Permite a la app llamar al API de AgroTrack en nombre del usuario |
| State | Enabled |

El scope completo queda `api://<CLIENT_ID>/access_as_user`: es lo que pide
MSAL y lo que valida el backend.

**Authorized client applications** (misma pantalla) → **+ Add a client
application** → pegar el `CLIENT_ID` → marcar el scope → Add. (La app se
autoriza a sí misma: evita la pantalla de consentimiento en cada login.)

## 4. Roles de aplicación

**App roles → + Create app role**, cuatro veces:

| Display name | Allowed member types | Value | Description |
|---|---|---|---|
| Administrador | Users/Groups | `ADMIN` | Catálogo, capacidad y KPIs de la red |
| Jefe de acopio | Users/Groups | `OPERADOR` | Recibe, clasifica y despacha |
| Productor | Users/Groups | `CLIENTE` | Registra y sigue sus entregas |
| Auditor | Users/Groups | `AUDITOR` | Solo lectura del timeline |

El **Value** es lo que llega en el claim `roles` del token y lo que
`JwtRolesConverter` convierte en `ROLE_ADMIN`, etc. Tiene que ser exactamente
así, en mayúsculas.

## 5. Asignar roles a personas

**Microsoft Entra ID → Enterprise applications → AgroTrack → Users and groups
→ + Add user/group** → elegir usuario → elegir rol → Assign.

Para la demo hacen falta al menos cuatro cuentas (una por rol) o una cuenta con
varios roles. En un tenant propio se crean usuarios en **Users → New user**.

## 6. Tokens v2 (manifest)

**Manifest** → buscar `"requestedAccessTokenVersion"` (o
`accessTokenAcceptedVersion` en el editor antiguo) → poner `2` → **Save**.

Con eso el access token trae `iss = https://login.microsoftonline.com/<TENANT_ID>/v2.0`,
que es el issuer que el enunciado indica para el JWT Authorizer. El backend
acepta como audience tanto `api://<CLIENT_ID>` (v1) como `<CLIENT_ID>` (v2).

## 7. Valores que salen de aquí

| Variable | Dónde va |
|---|---|
| `TENANT_ID` | `.env.aws` (`AZURE_TENANT_ID`), `environment.prod.ts`, JWT Authorizer |
| `CLIENT_ID` | `.env.aws` (`AZURE_API_CLIENT_ID`), `environment.prod.ts` (`clientId` y `apiScope`), JWT Authorizer (audience) |
| Issuer | `https://login.microsoftonline.com/<TENANT_ID>/v2.0` |
| JWKS | `https://login.microsoftonline.com/<TENANT_ID>/discovery/v2.0/keys` |

## 8. Probar el login real EN EL PC antes de ir a AWS

Sin tocar código, con la infraestructura local levantada:

```powershell
cd C:\Users\deint\Desktop\AgroTrack\infra\local
powershell -File .\azure-local.ps1 -TenantId <TENANT_ID> -ClientId <CLIENT_ID>
```

El script arranca los 8 servicios validando tokens **de Azure** (en vez de la
clave local), genera `environment.azure.ts` para el frontend y te dice cómo
levantarlo. Si el botón «Iniciar sesión con Microsoft» te lleva a Azure, vuelve
con tu nombre y el dashboard carga datos, la identidad está resuelta y lo que
quede es puro AWS.

Errores típicos:

| Síntoma | Causa |
|---|---|
| `AADSTS50011: redirect URI mismatch` | falta `http://localhost:4200` como SPA en Authentication |
| `AADSTS65001` / pantalla de consentimiento en bucle | falta autorizar la app en *Authorized client applications* |
| El backend responde 401 | `requestedAccessTokenVersion` no está en 2, o el scope pedido no es el de esta app |
| Entra pero todo da 403 | el usuario no tiene rol asignado en Enterprise applications |
