# 🧪 Manual de Pruebas de Seguridad y Simulación de Ataques - AgroTrack

**Proyecto:** AgroTrack Backend  
**Asignatura:** DSY1107 - Desarrollo Cloud Native I  
**Institución:** Duoc UC Concepción  
**Audiencia:** Equipo de Aseguramiento de Calidad (QA), Testers de Seguridad y Desarrolladores  

---

## 1. OBJETIVO DEL DOCUMENTO

Este manual detalla los procedimientos técnicos para ejecutar la suite de pruebas de seguridad automatizada de AgroTrack, así como las instrucciones paso a paso para **simular ataques reales** (fuerza bruta, denegación de servicio por agotamiento de conexiones, inyección JSON y violación de políticas CORS) y verificar las aserciones esperadas en cada respuesta.

---

## 2. EJECUCIÓN DE LA SUITE AUTOMATIZADA

Para ejecutar la totalidad de las pruebas unitarias y de integración de seguridad en el módulo perimetral `ms-agrotrack-bff`, ejecute el siguiente comando desde la raíz del microservicio:

```powershell
# En Windows PowerShell con JDK 21
$env:JAVA_HOME="C:\Program Files\JetBrains\IntelliJ IDEA 2024.3.1\jbr"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
./mvnw clean test
```

### Reporte de Pruebas Esperado:
El resultado debe arrojar **28 tests ejecutados, 0 fallos y 0 errores**:
- `BffSeguridadTest`: 9 tests de autorización y matriz RBAC.
- `JsonInjectionSecurityTest`: 4 tests de mitigación de inyección JSON y cumplimiento RFC 7807.
- `BffExceptionHandlerTest`: 4 tests de normalización de excepciones (`IOException`, `NPE`, `ResourceAccess`, genéricas).
- `HttpClientTimeoutTest`: 2 tests de corte de latencia en menos de 11 segundos.
- `RateLimiterSecurityTest`: 3 tests de umbral de 100 req/min y aislamiento por IP.
- `CorsSecurityTest`: 3 tests de validación estricta de protocolo HTTPS en producción.
- `LoggingFilterSecurityTest`: 3 tests de sanitización de credenciales y cabeceras.

---

## 3. SIMULACIÓN MANUAL DE ATAQUES Y VECTORES DE PRUEBA

A continuación se describen los escenarios de prueba manual mediante `curl` o herramientas de inspección HTTP (Postman / Burp Suite):

---

### 3.1 Ataque 1: Simulación de Inyección JSON y Payloads Maliciosos

- **Objetivo:** Comprobar que el BFF no colapse ni genere JSON malformado ante caracteres de control o inyección de propiedades en la URL.
- **Vector de Ataque:**
  ```bash
  curl -X GET "http://localhost:8081/api/hack%22%2C%22admin%22%3Atrue%2C%22injected%22%3A%22yes/test" \
       -H "Authorization: Bearer <TOKEN_VALIDO_JWT>" \
       -H "Accept: application/problem+json"
  ```
- **Respuesta Esperada:**
  - **Código de Estado:** `404 Not Found`.
  - **Cabecera `Content-Type`:** `application/problem+json`.
  - **Cuerpo JSON:**
    ```json
    {
      "type": "about:blank",
      "title": "Not Found",
      "status": 404,
      "detail": "No existe el servicio 'hack\",\"admin\":true,\"injected\":\"yes'",
      "instance": null
    }
    ```
- **Criterio de Validación:**
  1. El cuerpo es un JSON sintácticamente válido (puede ser parseado por `jq` o cualquier deserializador).
  2. La propiedad `"admin": true` o `"injected": "yes"` NO debe aparecer como un atributo de primer nivel en el objeto JSON.

---

### 3.2 Ataque 2: Simulación de Denegación de Servicio (DoS / Slowloris) por Latencia Downstream

- **Objetivo:** Verificar que peticiones hacia servicios congelados o con latencia excesiva se corten en menos de 11 segundos, liberando el hilo del servidor.
- **Vector de Ataque:**
  Simulación mediante un endpoint downstream retrasado o mock de latencia (5 segundos de espera):
  ```bash
  time curl -i -X GET "http://localhost:8081/api/deliveries/lento" \
       -H "Authorization: Bearer <TOKEN_VALIDO_JWT>"
  ```
- **Respuesta Esperada:**
  - **Tiempo de Respuesta:** Exactamente entre 5.0 y 5.5 segundos (estrictamente `< 11 segundos`).
  - **Código de Estado:** `503 Service Unavailable`.
  - **Cabecera `Content-Type`:** `application/problem+json`.
  - **Cuerpo JSON:**
    ```json
    {
      "type": "about:blank",
      "title": "Service Unavailable",
      "status": 503,
      "detail": "El servicio localhost:8082 no responde"
    }
    ```
- **Criterio de Validación:**
  El hilo de Tomcat es devuelto al pool inmediatamente después del corte y el servidor no presenta degradación de memoria.

---

### 3.3 Ataque 3: Ataque de Fuerza Bruta / Ráfaga DoS (Rate Limiting)

- **Objetivo:** Validar que ninguna dirección IP pueda emitir más de 100 peticiones por minuto.
- **Script de Simulación (PowerShell):**
  ```powershell
  # Emitir 105 peticiones consecutivas desde la misma IP
  $token = "<TOKEN_JWT>"
  for ($i = 1; $i -le 105; $i++) {
      $resp = Invoke-WebRequest -Uri "http://localhost:8081/api/deliveries" `
          -Headers @{ Authorization = "Bearer $token" } `
          -Method GET -SkipHttpErrorCheck
      Write-Host "Petición #$i -> Código: $($resp.StatusCode)"
  }
  ```
- **Respuesta Esperada:**
  - Peticiones 1 a 100: Códigos `200 OK` (o `401`/`403` según rol).
  - Petición 101 en adelante: Código `429 Too Many Requests`.
  - **Cabecera `Retry-After`:** Presente con valor `60`.
  - **Cuerpo JSON:**
    ```json
    {
      "type": "about:blank",
      "title": "Too Many Requests",
      "status": 429,
      "detail": "Demasiadas solicitudes. Límite de 100 peticiones por minuto excedido."
    }
    ```

---

### 3.4 Ataque 4: Suplantación de Origen en CORS (CORS Spoofing)

- **Objetivo:** Comprobar que en perfil de producción se prohíba el acceso desde orígenes que operen sobre HTTP inseguro.
- **Vector de Ataque:**
  ```bash
  curl -i -X OPTIONS "http://54.84.179.128:8081/api/deliveries" \
       -H "Origin: http://inseguro.dominio-atacante.com" \
       -H "Access-Control-Request-Method: GET"
  ```
- **Respuesta Esperada en Producción:**
  - La cabecera `Access-Control-Allow-Origin` **NO** se devuelve en la respuesta HTTP.
  - El navegador bloquea la comunicación en el cliente.

---

## 4. MATRIZ DE VERIFICACIÓN EN CADA REQUEST Y RESPONSE

Para cada prueba de endpoint en AgroTrack, verifique la siguiente lista de control:

| Componente | Verificación Obligatoria |
|---|---|
| **Cabecera `Content-Type`** | Debe ser `application/json` en respuestas exitosas y `application/problem+json` en errores. |
| **Cabecera `Authorization`** | Debe contener prefijo `Bearer ` con token JWT firmado y no expirado. |
| **Cabecera `X-Forwarded-By`** | Debe ser inyectada por el BFF con el valor `ms-agrotrack-bff`. |
| **Cabecera `Retry-After`** | Obligatoria en respuestas con código HTTP 429. |
| **Estructura RFC 7807** | Todo error debe incluir las claves: `"type"`, `"title"`, `"status"`, `"detail"`. |
| **Códigos de Estado** | `401` (Sin token), `403` (Rol sin permiso), `404` (No existe), `409` (Conflicto de negocio), `429` (Rate limit), `502`/`503` (Fallo de red downstream). |
