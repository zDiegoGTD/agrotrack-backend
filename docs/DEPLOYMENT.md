# 🚀 Guía de Despliegue, Operaciones y Respuesta ante Incidentes - AgroTrack

**Proyecto:** AgroTrack Backend  
**Asignatura:** DSY1107 - Desarrollo Cloud Native I  
**Institución:** Duoc UC Concepción  
**Audiencia:** Ingenieros DevOps, Operadores de Cloud (AWS) y Administradores de Sistemas  

---

## 1. INTRODUCCIÓN

Este documento define los requisitos operacionales, variables de entorno mandatorias, procedimientos de verificación previa al despliegue (*Pre-Deployment Checklist*) y el protocolo formal de **Respuesta ante Incidentes de Seguridad y Rollback** para el ecosistema de microservicios de AgroTrack en infraestructura cloud (AWS / Docker).

---

## 2. VARIABLES DE ENTORNO REQUERIDAS

Todas las variables sensibles deben inyectarse mediante variables de entorno del sistema operativo o mediante servicios de gestión de parámetros (AWS Systems Manager Parameter Store / Secrets Manager). **Nunca deben escribirse valores secretos en el repositorio.**

### 2.1 Variables Críticas del Perímetro (`ms-agrotrack-bff`)

| Variable | Tipo | Descripción | Ejemplo Productivo |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | String | Perfil de configuración activo (`local`, `dev`, `prod`). | `prod` |
| `SERVER_PORT` | Integer | Puerto TCP de escucha del servidor Tomcat embebido. | `8081` |
| `AGROTRACK_CORS_ORIGENES` | String | Lista separada por comas de orígenes permitidos. **En producción solo se admiten orígenes HTTPS**. | `https://app.agrotrack.cl,https://agrotrack.s3.amazonaws.com` |
| `AGROTRACK_HTTP_CLIENT_CONNECT_TIMEOUT_MS` | Integer | Tiempo límite en ms para establecer conexión TCP con microservicios. | `3000` |
| `AGROTRACK_HTTP_CLIENT_READ_TIMEOUT_MS` | Integer | Tiempo límite en ms para recibir datos de microservicios (debe ser `< 11000`). | `5000` |
| `AGROTRACK_RATE_LIMIT_MAX_REQUESTS_PER_MINUTE` | Integer | Número máximo de solicitudes permitidas por minuto por dirección IP. | `100` |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI` | URL | Endpoint JWKS público del Tenant de Microsoft Entra ID (Azure AD). | `https://login.microsoftonline.com/<TENANT_ID>/discovery/v2.0/keys` |
| `AGROTRACK_SERVICIOS_DELIVERIES` | URL | URL base del microservicio de entregas. | `http://deliveries:8082` |
| `AGROTRACK_SERVICIOS_CATALOG` | URL | URL base del microservicio de catálogo y bodegas. | `http://catalog:8083` |
| `AGROTRACK_SERVICIOS_REPORT` | URL | URL base del microservicio de reportería. | `http://report:8086` |
| `AGROTRACK_SERVICIOS_AUDIT` | URL | URL base del microservicio de auditoría. | `http://audit:8087` |

---

## 3. CHECKLIST PRE-DEPLOYMENT

Antes de promover una nueva versión a la instancia de producción en AWS EC2, el equipo de operaciones debe certificar:

### A. Integridad de Código y Pruebas
- [ ] La compilación limpia y la suite de pruebas automatizada se ejecutaron con éxito:
  ```powershell
  ./mvnw clean test
  ```
- [ ] No existen vulnerabilidades críticas en dependencias de terceros (`mvn dependency-check:check`).
- [ ] Se revisó el historial de commits en Git (`git log -n 5`) certificando que los cambios corresponden a versiones auditadas y aprobadas.

### B. Configuración y Secretos
- [ ] El perfil activo está configurado explícitamente como `SPRING_PROFILES_ACTIVE=prod`.
- [ ] La variable `AGROTRACK_CORS_ORIGENES` **NO** contiene ninguna URL que inicie con `http://`.
- [ ] Las credenciales de base de datos PostgreSQL y llaves de Azure AD no residen en archivos dentro de la imagen de contenedor.

### C. Red y Conectividad
- [ ] Los microservicios de dominio (`deliveries`, `catalog`, etc.) **NO están expuestos a Internet** (Security Groups de AWS limitan el tráfico entrante únicamente a la IP privada del BFF).
- [ ] El puerto `8081` del BFF solo es accesible a través del API Gateway de AWS.

---

## 4. PROCEDIMIENTO DE DESPLIEGUE CONTINUO EN AWS (DOCKER)

### 4.1 Construcción del Artefacto
```bash
# Empaquetado del microservicio BFF
./mvnw clean package -DskipTests=false
```

### 4.2 Construcción de la Imagen Docker
El Dockerfile implementa buenas prácticas de seguridad: ejecución con usuario sin privilegios (*non-root*):
```dockerfile
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser
WORKDIR /app
COPY target/ms-agrotrack-bff-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
```

```bash
docker build -t agrotrack/ms-agrotrack-bff:latest .
```

### 4.3 Despliegue con Docker Compose
```bash
# Lanzamiento en background con política de reinicio seguro
docker compose up -d ms-agrotrack-bff
```

---

## 5. PROTOCOLO DE RESPUESTA ANTE INCIDENTES DE SEGURIDAD

En caso de que el centro de operaciones detecte un comportamiento anómalo (tráfico inusualmente alto, anomalías en logs, errores masivos 500 o alertas de integridad), se debe activar de inmediato el siguiente protocolo de 4 fases (alineado con NIST SP 800-61 Rev. 2):

### Fase 1: Identificación y Aislamiento (Minuto 0 a 10)
1. **Verificar el estado del BFF:** Consultar el endpoint de diagnóstico `/actuator/health`.
2. **Inspeccionar trazas en vivo:**
   ```bash
   docker logs -f ms-agrotrack-bff --tail 200 | grep -E "WARN|ERROR"
   ```
3. **Aislar la IP Atacante:** Si se identifica un ataque de denegación de servicio o fuerza bruta desde una IP específica, bloquearla inmediatamente en el Security Group de AWS o en el Web Application Firewall (AWS WAF):
   ```bash
   aws ec2 authorize-security-group-ingress --group-id <SG_ID> --protocol tcp --port 8081 --cidr <IP_ATACANTE>/32 --rule-action deny
   ```

### Fase 2: Mitigación Inmediata de Rate Limiting
Si el tráfico malicioso proviene de múltiples IPs distribuidas, reduzca temporalmente el umbral de rate limiting sin reiniciar el servidor modificando la variable de entorno o ajustando el API Gateway:
```bash
# Reducir umbral a 30 peticiones/minuto de forma cautelar
export AGROTRACK_RATE_LIMIT_MAX_REQUESTS_PER_MINUTE=30
docker compose restart ms-agrotrack-bff
```

### Fase 3: Procedimiento de Rollback (Reversión a Versión Anterior)
Si el incidente se debe a un defecto o vulnerabilidad introducida en la última actualización de código:
1. Detener el contenedor actual:
   ```bash
   docker compose stop ms-agrotrack-bff
   ```
2. Desplegar la imagen previa estable etiquetada (*Previous Stable Tag*):
   ```bash
   docker tag agrotrack/ms-agrotrack-bff:previous agrotrack/ms-agrotrack-bff:latest
   docker compose up -d ms-agrotrack-bff
   ```
3. En el repositorio Git, revertir el commit causante:
   ```bash
   git revert HEAD --no-edit
   git push origin main
   ```

### Fase 4: Análisis Post-Mortem y Restauración
1. Recopilar los logs del sistema preservando la cadena de custodia digital.
2. Identificar el vector de ataque explotado (CWE asociado).
3. Redactar el informe de incidente con la causa raíz (RCA), impacto en el negocio y acciones correctivas a implementar en el siguiente sprint.
