# 🎓 Diapositivas y Guión de Defensa Oral - AgroTrack Seguridad
## Asignatura: DSY1107 - Desarrollo Cloud Native I (Duoc UC Concepción)
**Equipo:** Lian (Backend) + Diego (Frontend)  
**Tiempo Total Estimado:** 15 Minutos (2 min Problema + 5 min Solución + 5 min Implementación + 3 min Q&A)

---

## 📑 ÍNDICE DE DIAPOSITIVAS (SLIDES)

- **Slide 1:** Portada y Presentación del Proyecto
- **Slide 2:** Arquitectura Perimetral de AgroTrack y Rol del BFF
- **Slide 3:** Diagnóstico Inicial: Matriz de Vulnerabilidades
- **Slide 4:** Problema Crítico 1: Inyección JSON (CWE-116 / OWASP A03)
- **Slide 5:** Problema Crítico 2: Ausencia de Timeouts HTTP y DoS (CWE-400 / Slowloris)
- **Slide 6:** Problemas Importantes: Ausencia de Handler, Rate Limiter y CORS
- **Slide 7:** Arquitectura de Seguridad Diseñada (Zero Trust y Defensa en Profundidad)
- **Slide 8:** Solución Técnica 1: Serialización Segura con `ProblemDetail` y `ObjectMapper`
- **Slide 9:** Solución Técnica 2: Timeouts y Resiliencia con `HttpClientConfig`
- **Slide 10:** Solución Técnica 3: Control de Acceso Global con `BffExceptionHandler`
- **Slide 11:** Mejoras de Seguridad: Rate Limiting por IP y Logging Sanitizado
- **Slide 12:** Estándares Internacionales Aplicados (ISO 27001 / NIST CSF 2.0)
- **Slide 13:** Implementación en Código: Estructura del Módulo `ms-agrotrack-bff`
- **Slide 14:** Estrategia de Pruebas Automatizadas (>15 Tests)
- **Slide 15:** Resultados de Ejecución y Métricas de Seguridad (100% PASS)
- **Slide 16:** Demostración en Vivo: Simulación de Inyección y Timeouts
- **Slide 17:** Lecciones Aprendidas y Roadmap Futuro
- **Slide 18:** Conclusiones Finales
- **Slide 19:** Sesión de Preguntas y Respuestas (Q&A)

---

## 🖥️ CONTENIDO DE LAS DIAPOSITIVAS Y GUIÓN DE EXPOSICIÓN

---

### SLIDE 1: Portada y Presentación
```
================================================================================
                    AGROTRACK: EVALUACIÓN DE SEGURIDAD Y MEJORAS
           Auditoría, Remediación y Resiliencia en Arquitecturas Cloud Native
================================================================================
Asignatura: DSY1107 - Desarrollo Cloud Native I | Duoc UC Sede Concepción
Expositores: 
  - Lian (Ingeniería de Backend & Arquitectura de Microservicios)
  - Diego (Ingeniería de Frontend & Consumo Seguro de APIs)
Docente: Profesor de Cátedra DSY1107
Fecha: Septiembre 2026
```
> **🎙️ Guión (0:00 - 0:45) - Lian:**  
> "Buenos días profesor y compañeros. Hoy junto a Diego presentamos la evaluación de seguridad, auditoría técnica y remediación integral desarrollada sobre el backend de **AgroTrack**, nuestra plataforma cloud-native de acopio y despacho agrícola. En esta exposición demostraremos cómo identificamos vulnerabilidades críticas en el Backend-for-Frontend, cómo rediseñamos su arquitectura bajo principios de Zero Trust y cómo implementamos mejoras robustas acompañadas de 28 pruebas automatizadas con 100% de éxito."

---

### SLIDE 2: Arquitectura Perimetral y el Rol del BFF
```
┌─────────────────┐       ┌─────────────────┐       ┌────────────────────────┐
│   Angular SPA   │ ────▶ │ AWS API Gateway │ ────▶ │    ms-agrotrack-bff    │
│  (MSAL / PKCE)  │       │ (Valida Firma)  │       │ (Filtros, Rate Limiter,│
└─────────────────┘       └─────────────────┘       │  RBAC, Reenvío Seguro) │
                                                    └───────────┬────────────┘
                                                                │ Bearer Token
                                                    ┌───────────┴────────────┐
                                                    ▼                        ▼
                                            [ms-deliveries]            [ms-catalog]
```
> **🎙️ Guión (0:45 - 1:30) - Diego:**  
> "En AgroTrack, la interfaz de usuario en Angular consume los microservicios a través de una cadena perimetral. El usuario autentica con Microsoft Entra ID mediante Authorization Code + PKCE. El API Gateway recibe el token JWT y lo deriva a nuestro punto único de entrada: el BFF. El BFF es el corazón de la seguridad perimetral: filtra orígenes CORS, previene ataques de fuerza bruta, aplica la matriz de roles y reenvía las solicitudes a los microservicios de dominio."

---

### SLIDE 3: Diagnóstico Inicial: Matriz de Vulnerabilidades
```
┌─────────┬──────────────────────────────────┬────────────┬─────────────┬───────────┐
│ ID      │ Vulnerabilidad Identificada      │ CWE        │ OWASP       │ CVSS v3.1 │
├─────────┼──────────────────────────────────┼────────────┼─────────────┼───────────┤
│ VULN-01 │ Inyección JSON en Proxy          │ CWE-116/20 │ A03:2021    │ 7.5 High  │
│ VULN-02 │ Inyección JSON en Reenviador     │ CWE-116/20 │ A03:2021    │ 7.5 High  │
│ VULN-03 │ Sin Timeouts HTTP (DoS)          │ CWE-400    │ API4:2023   │ 7.5 High  │
│ VULN-04 │ Sin Exception Handler Global     │ CWE-755    │ A05:2021    │ 5.3 Med   │
│ VULN-05 │ Sin Rate Limiting por IP         │ CWE-770    │ API4:2023   │ 5.3 Med   │
│ VULN-06 │ CORS Permisivo (HTTP en Prod)    │ CWE-942    │ A05:2021    │ 4.3 Med   │
│ VULN-07 │ Credenciales en Logs             │ CWE-532    │ A09:2021    │ 5.3 Med   │
└─────────┴──────────────────────────────────┴────────────┴─────────────┴───────────┘
```
> **🎙️ Guión (1:30 - 2:00) - Lian:**  
> "Al auditar el código base identificamos 7 vulnerabilidades prioritarias: 3 de severidad crítica (CVSS 7.5) asociadas a inyección JSON y denegación de servicio por ausencia de timeouts, y 4 de severidad media que comprometían el manejo de errores, la tasa de peticiones, la política CORS y la confidencialidad de los logs."

---

### SLIDE 4: Problema Crítico 1: Inyección JSON (CWE-116 / OWASP A03)
```java
// ❌ CÓDIGO VULNERABLE ANTERIOR (ProxyController.java)
private static ResponseEntity<byte[]> problema(HttpStatus status, String detalle) {
    ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detalle);
    String json = "{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase() + "\",\"status\":" + status.value()
            + ",\"detail\":\"" + detalle.replace("\"", "'") + "\"}";
    return ResponseEntity.status(status).header(HttpHeaders.CONTENT_TYPE, "application/problem+json")
            .body(json.getBytes());
}
```
- **Riesgo:** Concatenación manual de cadenas con `replace("\"", "'")`.
- **Impacto:** Si `detalle` contiene caracteres de escape, saltos de línea (`\n`) o inyecciones, el JSON queda inválido, rompiendo la aplicación frontend del cliente.
> **🎙️ Guión (2:00 - 3:00) - Lian:**  
> "Observen este fragmento en ProxyController. La respuesta de error se armaba mediante interpolación manual de cadenas. Si el detalle del error traía comillas, llaves o saltos de línea, el reemplazo simple con comillas simples fallaba, generando un JSON malformado. Esto rompía el deserializador de Angular, dejando la pantalla en blanco y exponiendo la API a inyección de propiedades."

---

### SLIDE 5: Problema Crítico 2: Ausencia de Timeouts HTTP (CWE-400 / DoS)
```
[Atacante / Microservicio Lento] 
              │ Retiene la conexión o demora 60 segundos
              ▼
    [ms-agrotrack-bff] ──▶ Tomcat Thread Pool (100% Ocupado esperando I/O)
              │
    [Usuarios Legítimos] ──▶ ❌ TIMEOUT / SERVICIO CAÍDO (Colapso en cascada)
```
- **Deficiencia:** `RestClient` utilizaba el builder por defecto sin configurar Connect Timeout ni Read Timeout.
- **Impacto:** Si `deliveries` o `catalog` se degradaban, los hilos del BFF quedaban bloqueados indefinidamente, provocando agotamiento de recursos (*Thread Starvation*).
> **🎙️ Guión (3:00 - 4:00) - Diego:**  
> "El segundo bug crítico residía en los clientes de red. Al no tener configurados timeouts de lectura, una demora o ataque tipo Slowloris en un microservicio interno congelaba los hilos de Tomcat del BFF. En microservicios esto es letal: el fallo de un servicio periférico terminaba derribando toda la plataforma."

---

### SLIDE 6: Arquitectura de Seguridad Rediseñada
```
                    NUEVA CADENA DE FILTROS EN ms-agrotrack-bff
┌─────────────────────────────────────────────────────────────────────────────┐
│ 1. LoggingFilter       ▶ Sanitiza passwords, tokens y query strings         │
│ 2. RateLimiterFilter   ▶ Exige máx 100 req/min por IP (Retorna 429)         │
│ 3. SecurityConfig      ▶ Exige HTTPS en Prod + Valida JWT + Matriz RBAC     │
│ 4. BffExceptionHandler ▶ Captura IOException/NPE/503 y estandariza RFC 7807 │
│ 5. HttpClientConfig    ▶ Corta llamadas salientes a los 5s (< 11s)          │
└─────────────────────────────────────────────────────────────────────────────┘
```
> **🎙️ Guión (4:00 - 5:30) - Lian:**  
> "Para remediar esto diseñamos una arquitectura defensiva multicapa. Cada petición entrante debe superar secuencialmente: nuestro filtro de logging seguro, el limitador de tasa por IP, la validación estricta de CORS y roles de Spring Security, y al salir, las llamadas son custodiadas por timeouts estrictos y manejadores de error centralizados."

---

### SLIDE 7: Solución a Inyección JSON y Timeouts (Código Implementado)
```java
// ✅ SOLUCIÓN SEGURA 1: Jackson ObjectMapper + ProblemDetail RFC 7807
ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detalle);
p.setTitle(status.getReasonPhrase());
p.setType(URI.create("about:blank"));
byte[] jsonBytes = objectMapper.writeValueAsBytes(p); // 100% Escapado y Estructurado

// ✅ SOLUCIÓN SEGURA 2: HttpClientConfig (< 11 segundos)
SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
factory.setConnectTimeout(Duration.ofMillis(3000)); // 3 segundos de conexión
factory.setReadTimeout(Duration.ofMillis(5000));    // 5 segundos de lectura
```
> **🎙️ Guión (5:30 - 7:00) - Lian:**  
> "En pantalla pueden ver las soluciones concretas. Reemplazamos la concatenación manual inyectando el `ObjectMapper` de Jackson, el cual garantiza que toda respuesta `application/problem+json` sea matemáticamente válida y segura. En segundo lugar, creamos `HttpClientConfig` estableciendo 3 segundos para conectar y 5 segundos para leer, asegurando que cualquier servicio degradado sea abortado en menos de 11 segundos."

---

### SLIDE 8: Manejador Global de Excepciones (`BffExceptionHandler`)
```java
@RestControllerAdvice
public class BffExceptionHandler {
    @ExceptionHandler(IOException.class)
    public ResponseEntity<ProblemDetail> handleIOException(IOException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Fallo de red al procesar la solicitud"));
    }
    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<ProblemDetail> handleNullPointerException(NullPointerException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno del servidor"));
    }
    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ProblemDetail> handleResourceAccess(ResourceAccessException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Servicio de destino no responde"));
    }
}
```
> **🎙️ Guión (7:00 - 8:30) - Diego:**  
> "Implementamos `BffExceptionHandler` con `@RestControllerAdvice`. Con esto eliminamos completamente las páginas Whitelabel de Spring. Cualquier fallo de I/O retorna un 502, una caída de microservicio retorna 503, y cualquier error imprevisto retorna un 500 limpio, siempre con cabecera `application/problem+json` y el `status` numérico en el cuerpo JSON, facilitando el manejo de errores en el frontend."

---

### SLIDE 9: Rate Limiting y Logging Seguro
```
RATE LIMITER:
- Sliding Window en memoria concurrente (ConcurrentHashMap + AtomicInteger).
- 100 req/min por IP.
- Petición 101 rechazada con HTTP 429 y cabecera "Retry-After: 60".

LOGGING SEGURO:
- Regex intercepta: password, token, secret, access_token.
- Sanitiza query string: ?status=RECIBIDA&token=***
- Cabecera Authorization enmascarada a "Bearer ***".
```
> **🎙️ Guión (8:30 - 10:00) - Lian:**  
> "Agregamos además las dos mejoras solicitadas: Rate Limiter y Logging Seguro. El Rate Limiter rastrea concurrentemente las peticiones por IP, bloqueando cualquier ráfaga que supere las 100 solicitudes por minuto. Y nuestro `LoggingFilter` inspecciona las peticiones entrantes para enmascarar automáticamente tokens y contraseñas, evitando fugas en CloudWatch o en los logs del servidor bajo la norma CWE-532."

---

### SLIDE 10: Resultados de la Verificación y Tests Automatizados
```
================================================================================
                               RESULTADOS SUREFIRE
================================================================================
[INFO] Running cl.agrotrack.bff.config.CorsSecurityTest              (3/3 PASS)
[INFO] Running cl.agrotrack.bff.config.HttpClientTimeoutTest        (2/2 PASS)
[INFO] Running cl.agrotrack.bff.config.LoggingFilterSecurityTest    (3/3 PASS)
[INFO] Running cl.agrotrack.bff.config.RateLimiterSecurityTest      (3/3 PASS)
[INFO] Running cl.agrotrack.bff.infraestructura.web.BffExceptionHandlerTest (4/4 PASS)
[INFO] Running cl.agrotrack.bff.infraestructura.web.BffSeguridadTest (9/9 PASS)
[INFO] Running cl.agrotrack.bff.infraestructura.web.JsonInjectionSecurityTest (4/4 PASS)
--------------------------------------------------------------------------------
[INFO] Tests run: 28, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS (Total time: 10.028 s)
================================================================================
```
> **🎙️ Guión (10:00 - 11:30) - Diego:**  
> "La rúbrica exigía mínimo 15 nuevos tests. Nosotros desarrollamos **19 tests nuevos**, totalizando **28 pruebas automatizadas** en el BFF. Validamos inyección JSON, corte estricto de timeouts con un servidor HTTP real, 429 en la petición 101, rechazo de orígenes inseguros en producción y sanitización de credenciales. La suite completa se ejecuta en 10 segundos y el 100% de las pruebas pasa exitosamente."

---

### SLIDE 11: Demostración en Vivo
```bash
# 1. Ejecución de la suite completa de seguridad en terminal:
./mvnw clean test

# 2. Verificación de corte de timeout en menos de 11 segundos:
# HttpClientTimeoutTest -> Corte a los 1.000 ms ante servicio de 5.000 ms (PASS)

# 3. Verificación de Rate Limiter:
# RateLimiterSecurityTest -> 100 permitidas, 101 rechazada con HTTP 429 (PASS)
```
> **🎙️ Guión (11:30 - 13:00) - Lian & Diego:**  
> *(Demostración en pantalla del IDE y terminal ejecutando `./mvnw clean test` y mostrando los 28 tests verdes).*  
> "En pantalla pueden observar la consola. Al lanzar `mvn clean test`, Spring Boot compila con Java 21, carga los contextos de seguridad y ejecuta cada una de las pruebas de penetración y resiliencia en tiempo real, confirmando el `BUILD SUCCESS` sin fallos."

---

### SLIDE 12: Conclusiones y Preguntas Frecuentes (Q&A)
```
CONCLUSIONES:
1. La seguridad perimetral del BFF pasó de ser vulnerable a tener resiliencia de grado industrial.
2. Cumplimiento integral de estándares: OWASP Top 10, RFC 7807/9457, ISO/IEC 27001 y NIST CSF 2.0.
3. Repositorio documentado con INFORME TÉCNICO, SECURITY.md, TESTING_SEGURIDAD.md y DEPLOYMENT.md.

PREGUNTAS PROBABLES DEL DOCENTE:
Q1: ¿Por qué usar ObjectMapper en vez de regex para limpiar el JSON?
Q2: ¿Por qué 11 segundos como límite máximo de timeout?
Q3: ¿Cómo protege el BFF si un microservicio interno se cae totalmente?
```
> **🎙️ Guión (13:00 - 15:00) - Lian & Diego:**  
> "Para concluir, hemos demostrado que la seguridad no es simplemente agregar un login, sino diseñar mecanismos de contención, tolerancia a fallos y validación rigurosa en cada capa. Agradecemos su atención y quedamos a total disposición para responder las preguntas de la comisión."

---

## ❓ BANCO DE RESPUESTAS PARA PREGUNTAS DE LA COMISIÓN

### P1: "¿Por qué prefirieron serializar con Jackson en vez de usar una función de limpieza o reemplazar comillas?"
> **Respuesta Lian:** *"Profesor, la sanitización manual basada en expresiones regulares o reemplazo de caracteres es una práctica desaconsejada por OWASP (CWE-116). Siempre existe un vector de ataque que elude la expresión regular (como bytes nulos, secuencias Unicode `\u0022` o saltos de línea). Jackson analiza la gramática completa del estándar RFC 8259 y escapa adecuadamente cada byte, garantizando que el documento resultante sea matemáticamente válido e inmune a inyecciones."*

### P2: "¿Por qué fijaron el timeout de lectura en 5 segundos si la pauta pedía menos de 11 segundos?"
> **Respuesta Diego:** *"Fijamos 3 segundos de conexión y 5 segundos de lectura porque en una arquitectura orientada a microservicios, el tiempo de respuesta promedio de una API debe situarse por debajo de los 500 ms. Si un microservicio tarda más de 5 segundos, ya se encuentra degradado; cortar a los 5 segundos libera los recursos de Tomcat con un amplio margen de seguridad sobre los 11 segundos requeridos, evitando la frustración del usuario en la interfaz."*

### P3: "¿Qué sucede con el Rate Limiter si la aplicación se despliega en múltiples instancias de contenedor?"
> **Respuesta Lian:** *"Nuestra implementación actual utiliza `ConcurrentHashMap` con ventana deslizante en memoria, lo que es óptimo para la instancia desplegada en EC2. En un escenario de escalabilidad horizontal con auto-scaling, la arquitectura propuesta en nuestro informe técnico contempla desacoplar el contador hacia un clúster de Redis mediante Spring Data Redis o un token bucket distribuido en AWS WAF."*
