# 🛡️ Guía de Seguridad para Desarrolladores - AgroTrack

**Proyecto:** AgroTrack Backend  
**Asignatura:** DSY1107 - Desarrollo Cloud Native I  
**Institución:** Duoc UC Concepción  
**Audiencia:** Desarrolladores de Backend, Ingenieros de Software y Contribuidores del Repositorio  

---

## 1. INTRODUCCIÓN Y FILOSOFÍA "SECURITY-FIRST"

En una arquitectura de microservicios distribuida como AgroTrack, la seguridad no es una capa externa ni un atributo que se añade al final del ciclo de vida del desarrollo. La seguridad es un **requisito no funcional mandatorio** que debe regir cada decisión de diseño, modelado de dominio y codificación.

Esta guía establece las directrices obligatorias, patrones arquitectónicos y checklists de verificación para asegurar que cualquier nuevo endpoint, servicio o modificación mantenga la integridad y resiliencia del sistema frente a amenazas del **OWASP Top 10** y **OWASP API Security Top 10**.

---

## 2. ANTI-PATRONES COMUNES Y CÓMO EVITARLOS

### 2.1 Anti-Patrón 1: Concatenación Manual de JSON (CWE-116 / CWE-20)
- **El Error:** Construir respuestas JSON o fragmentos de texto usando interpolación o suma de cadenas (`"{\"error\":\"" + msg + "\"}"`).
- **El Riesgo:** Si la variable `msg` contiene comillas dobles (`"`), caracteres de control (`\n`, `\r`, `\t`), o payloads maliciosos, la estructura del documento JSON se rompe, causando fallos de deserialización en el cliente o inyección de campos no autorizados.
- **La Solución Segura:** Utilizar siempre un serializador estructurado como `ObjectMapper` (Jackson) o la clase estándar `org.springframework.http.ProblemDetail` de Spring Framework 6.

```java
// ❌ INSEGURO: Vulnerable a inyección y corrupción sintáctica
String json = "{\"status\":400,\"detail\":\"" + userDetail.replace("\"", "'") + "\"}";
return ResponseEntity.badRequest().body(json);

// ✅ SEGURO: Uso de ProblemDetail y serialización con Jackson
ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, userDetail);
problem.setTitle("Bad Request");
problem.setType(URI.create("about:blank"));
byte[] safeJson = objectMapper.writeValueAsBytes(problem);
return ResponseEntity.badRequest()
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(safeJson);
```

### 2.2 Anti-Patrón 2: Clientes HTTP sin Timeouts (CWE-400 / CWE-770)
- **El Error:** Instanciar `RestClient` o `RestTemplate` usando los constructores o builders por defecto sin asociar una factoría de peticiones con límites temporales explícitos.
- **El Riesgo:** Si el microservicio de destino se bloquea por un deadlock o latencia de red, los hilos de Tomcat del BFF se agotan (*Thread Starvation*), derribando el sistema ante ataques Slowloris.
- **La Solución Segura:** Utilizar siempre `HttpClientConfig` que establece `connectTimeout` (máximo 3 segundos) y `readTimeout` (máximo 5 segundos).

```java
// ❌ INSEGURO: Sin límites de tiempo
RestClient client = RestClient.builder().build();

// ✅ SEGURO: Configuración de timeout explícita (< 11 segundos)
SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
factory.setConnectTimeout(Duration.ofMillis(3000));
factory.setReadTimeout(Duration.ofMillis(5000));
RestClient client = RestClient.builder().requestFactory(factory).build();
```

### 2.3 Anti-Patrón 3: Fuga de Información en Respuestas de Error (CWE-209 / CWE-755)
- **El Error:** Devolver trazas de excepciones (`e.printStackTrace()`), nombres de tablas o clases internas directamente en el cuerpo de la respuesta HTTP.
- **El Riesgo:** Facilita el reconocimiento a atacantes al exponer versiones de librerías, dialectos SQL o estructura de base de datos.
- **La Solución Segura:** Centralizar el manejo en `BffExceptionHandler` (`@RestControllerAdvice`), registrando los detalles en logs internos y entregando al cliente un mensaje genérico y seguro.

---

## 3. PATRONES SEGUROS DE AUTENTICACIÓN Y AUTORIZACIÓN

### 3.1 Manejo de Tokens JWT y Propagación
1. **Validación Perimetral e Interna (Defensa en Profundidad):**
   - El API Gateway valida la firma criptográfica contra los endpoints JWKS de Microsoft Entra ID.
   - El BFF valida nuevamente el token y comprueba la matriz de roles en `SecurityConfig`.
   - Cada microservicio de dominio valida el token de manera autónoma con anotaciones `@PreAuthorize("hasRole('ADMIN')")`.
2. **Propagación Transparente:**
   - El BFF nunca debe suplantar la identidad del usuario ni generar tokens con privilegios elevados ("god-mode"). El token `Bearer` del usuario original se reenvía sin alteraciones en la cabecera `Authorization`.

### 3.2 Matriz de Roles en AgroTrack

| Rol | Permisos Otorgados | Restricciones |
|---|---|---|
| `ROLE_ADMIN` | Acceso total: entregas, catálogo, reportes KPI y auditoría. | Sujeto a auditoría inmutable de eventos. |
| `ROLE_OPERADOR` | Modificación de estados de entrega y reserva de capacidad en bodegas. | No puede ver reportes gerenciales ni eliminar productos. |
| `ROLE_CLIENTE` | Registro de nuevas entregas agrícolas y consulta de entregas propias. | No puede cambiar estados de entrega ni reservar capacidad. |
| `ROLE_AUDITOR` | Consulta de trazas de auditoría (`/api/audit/**`) en modo solo lectura. | Prohibida cualquier operación de mutación (POST, PUT, DELETE). |

---

## 4. GUÍA DE LOGGING SEGURO (CWE-532)

El almacenamiento indiscriminado de datos en registros (*logs*) constituye una de las principales fuentes de filtración de credenciales (OWASP A09:2021).

### Reglas de Oro para Logging en AgroTrack:
1. **PROHIBIDO:** Registrar contraseñas, secretos, llaves maestras o números de tarjeta.
2. **PROHIBIDO:** Imprimir el token JWT completo en texto claro. En su lugar, utilice `LoggingFilter.sanitizarCabecera()`, la cual enmascara la cabecera a `Bearer ***`.
3. **PROHIBIDO:** Concatenar URLs completas que incluyan query strings con parámetros sensibles como `?token=...` o `?password=...`. Emplee siempre `LoggingFilter.sanitizarQueryString()`.
4. **OBLIGATORIO:** Propagar e incluir la cabecera `traceparent` (W3C Trace Context) en cada línea de log para correlación distribuida de peticiones.

---

## 5. VALIDACIÓN DE ENTRADAS Y ENCODING DEFENSIVO

1. **Parámetros de Ruta y Query:**
   - Valide tipos de datos estrictos (`Long`, `UUID`, enumeraciones tipadas).
   - No confíe en los datos provenientes de `@RequestParam`. Asegúrese de que Spring MVC aplique validadores `@Valid` y expresiones regulares en el DTO receptor.
2. **Cuerpos de Solicitud (`@RequestBody`):**
   - Todos los DTOs deben implementar Bean Validation (`jakarta.validation.constraints`):
     - `@NotBlank`, `@Size(max = 100)`
     - `@Min(1)`, `@Max(10000)`
     - `@Pattern(regexp = "^[a-zA-Z0-9_-]+$")`

---

## 6. CHECKLIST DE SEGURIDAD PRE-COMMIT

Antes de realizar `git commit` y solicitar un Pull Request, cada desarrollador debe verificar:

- [ ] **1. Sin secretos en el código:** No existen passwords, API keys, Connection Strings de base de datos ni tokens en archivos `.java`, `.properties` o `.yml`.
- [ ] **2. JSON seguro:** Ninguna respuesta HTTP concatena cadenas manualmente. Todas las respuestas de error utilizan `ProblemDetail` y `ObjectMapper`.
- [ ] **3. Timeouts activos:** Toda llamada HTTP saliente utiliza un cliente con timeouts configurados (< 11 segundos).
- [ ] **4. Control de acceso explícito:** El nuevo endpoint está debidamente protegido en `SecurityConfig` o con `@PreAuthorize`.
- [ ] **5. Logging limpio:** Las trazas no imprimen datos confidenciales ni tokens.
- [ ] **6. Tests automatizados:** Se han escrito pruebas unitarias y de integración que validan tanto el camino feliz como las condiciones de error (códigos 400, 401, 403, 404, 429, 503).
- [ ] **7. Suite en verde:** `./mvnw clean test` compila sin advertencias y el 100% de las pruebas finalizan con `BUILD SUCCESS`.

---

## 7. CONTROLES AGREGADOS DESPUÉS DE LA PRIMERA VERSIÓN DE ESTA GUÍA

| Control | Dónde | Detalle |
|---|---|---|
| **Scope requerido** | API Gateway (ruta `ANY /api/{proxy+}`) y BFF (`ValidacionTokenConfig`) | Además del rol, el token debe traer `scp` = `access_as_user`. Sin él: 403 en el Gateway, 401 `SCOPE_INSUFICIENTE` en el BFF |
| **Gateway como única puerta** | `FiltroOrigenGateway` | El Gateway agrega `X-Origen-Gateway` con un secreto; lo que llega directo a la EC2 recibe 403 `ORIGEN_NO_PERMITIDO`. Comparación en tiempo constante |
| **Rechazos con motivo** | `RespuestasSeguridad` | 401/403 con `WWW-Authenticate` y `problem+json` con `codigo` (ver `00-decisiones.md`, D10) |
| **Límite de peticiones no evadible** | `RateLimiterFilter` | La IP se toma del **último** valor de `X-Forwarded-For` (el que agrega el API Gateway); el primero lo escribe el cliente y se puede inventar |
| **CORS solo HTTPS en AWS** | `SecurityConfig` | La restricción aplica a los perfiles `prod`, `production` **y `aws`** (el del despliegue real) |
| **Cuenta aprobada** | `FiltroCuentaActiva` + `ms-agrotrack-users` | Token con rol **y** cuenta `ACTIVA`; si `users` no responde, 503 |
| **Solo usuarios con rol** | Azure AD, *Assignment required* | Microsoft rechaza en el login a quien no tiene un App Role asignado |

Nota sobre `RateLimiterFilter` y `LoggingFilter`: están registrados como
`@Component` y además se agregan a la cadena de Spring Security. Aun así se
ejecutan **una sola vez** por petición, porque heredan de
`OncePerRequestFilter`, que marca la petición y se salta la segunda pasada.
