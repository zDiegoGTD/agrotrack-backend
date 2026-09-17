# Administración de usuarios — plan de implementación


**Goal:** Que todo usuario quede registrado en PostgreSQL al entrar por primera vez y que el administrador lo apruebe, rechace, desactive o reactive desde la web; el BFF bloquea a quien no esté `ACTIVO`.

**Architecture:** Microservicio nuevo `ms-agrotrack-users` (Spring Boot, base `agro_users`), con la misma forma que `ms-agrotrack-catalog`. El BFF sincroniza al usuario en `/api/me` y un filtro posterior a la matriz de roles consulta el estado (caché de 60 s) antes de reenviar. El frontend añade `estadoGuard`, `/pendiente`, `/usuarios` y `/mi-perfil`.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring Security 6 resource server, Spring Data JPA, Flyway, PostgreSQL 16, Testcontainers · Angular 22 (signals, standalone), Vitest · Docker Compose, AWS API Gateway.

**Spec:** `docs/planes/2026-09-08-administracion-usuarios-design.md`

## Global Constraints

- Los roles **siguen viniendo del claim `roles` del token**. `ROL_ULTIMO_TOKEN` es informativo: nunca se usa para autorizar.
- Regla efectiva: **token con el rol Y usuario `ACTIVO`**.
- Estados: `PENDIENTE` · `ACTIVO` · `RECHAZADO` · `INACTIVO`. Transiciones: PENDIENTE→ACTIVO, PENDIENTE→RECHAZADO (motivo), ACTIVO→INACTIVO (motivo), INACTIVO→ACTIVO, RECHAZADO→ACTIVO.
- Primer admin: mientras no exista ningún `ACTIVO`, el primero que entra con `ADMIN` en el token nace `ACTIVO` con `APROBADO_POR = 'sistema'`. Nunca un usuario sin `ADMIN`.
- Un admin no puede desactivarse a sí mismo: 409.
- Códigos 403 del BFF: `CUENTA_PENDIENTE` "Tu cuenta espera aprobación", `CUENTA_RECHAZADA` "Tu solicitud fue rechazada", `CUENTA_INACTIVA` "Tu cuenta fue desactivada".
- Caché de estado en el BFF: 60 s. Si `users` no responde: **503**, nunca dejar pasar.
- `/api/me` queda fuera del filtro (es por donde se registra y por donde `/pendiente` sabe el estado).
- Puerto local de `users`: **8089**. Base y rol: `agro_users` / contraseña `agro_users`.
- Errores RFC 7807 (`application/problem+json`) con propiedad `codigo` cuando hay un código de negocio.
- Spring Boot **3.5.16**, no 4. Nombres de tablas y columnas en minúscula sin comillas (como el resto).
- Código y comentarios en español, sin tildes en identificadores; misma densidad de comentarios que `ms-agrotrack-catalog`.
- Backend en el repo `AgroTrack` (agrotrack-backend); frontend en `AgroTrack/frontend-agrotrack` (repo aparte, se commitea ahí).

## Cómo correr las cosas (Windows, Git Bash)

```bash
export JAVA_HOME="/c/Users/deint/tools/jdk-21.0.12.1+1"
cd /c/Users/deint/Desktop/AgroTrack/ms-agrotrack-users && ./mvnw -q test          # Docker Desktop encendido (Testcontainers)
cd /c/Users/deint/Desktop/AgroTrack/frontend-agrotrack && npx ng test --watch=false
```

## Mapa de archivos

**Nuevo `ms-agrotrack-users/`** (`src/main/java/cl/agrotrack/users/`):

| Archivo | Responsabilidad |
|---|---|
| `MsAgrotrackUsersApplication.java` | arranque |
| `config/SecurityConfig.java`, `config/JwtRolesConverter.java` | resource server, roles → `ROLE_*` |
| `config/RelojConfig.java` | `Clock` inyectable |
| `dominio/EstadoUsuario.java` | enum de estados |
| `dominio/MotivoRechazoCambio.java` | por qué se rechaza un cambio |
| `dominio/CambioEstadoInvalidoException.java` | excepción con motivo |
| `dominio/MaquinaEstadosUsuario.java` | clase pura: estado inicial y cambios válidos |
| `aplicacion/IdentidadToken.java` | claims que importan (oid, email, nombre, roles) |
| `aplicacion/Dtos.java` | requests/responses |
| `aplicacion/UsuarioService.java` | sincronizar, listar, obtener, me, cambiar estado, perfil |
| `aplicacion/RecursoNoEncontradoException.java`, `aplicacion/RutDuplicadoException.java` | errores de aplicación |
| `infraestructura/persistencia/Usuario.java`, `PerfilProductor.java`, `UsuarioRepository.java`, `PerfilProductorRepository.java` | JPA |
| `infraestructura/web/UsuarioController.java`, `ApiExceptionHandler.java` | HTTP |
| `resources/application*.yml`, `db/migration/V1__users.sql`, `jwt/local-public.pem` | config y esquema |

**BFF** (`ms-agrotrack-bff/src/main/java/cl/agrotrack/bff/`): nuevo paquete `cuenta/` (`CuentaUsuario`, `ClienteUsuarios`, `ClienteUsuariosHttp`, `UsuariosNoDisponibleException`, `EstadoCuentas`, `FiltroCuentaActiva`), `config/RelojConfig.java`; modificar `config/SecurityConfig.java`, `infraestructura/web/MeController.java`, `application.yml`.

**Infra/docs:** `infra/local/init-postgres/02-users.sql`, `infra/apps/compose.yml`, `infra/.env.aws.example`, `infra/aws/crear.ps1`, `infra/local/smoke.ps1`, `infra/local/azure-local.ps1`, `infra/aws/smoke-aws.ps1`, `README.md`, `docs/06-modelo-de-datos.md`, `docs/10-checklist-demo.md`.

**Frontend** (`frontend-agrotrack/src/app/`): `core/models.ts`, `core/api/api.service.ts`, nuevo `core/auth/cuenta.service.ts`, `core/auth/guards.ts` (+ `guards.spec.ts`), nuevo `core/usuarios.ts` (+ `usuarios.spec.ts`), `app.routes.ts`, `shell/shell.ts`, nuevas `pages/pendiente/pendiente.ts`, `pages/usuarios/usuarios.ts`, `pages/mi-perfil/mi-perfil.ts`.

---

### Task 1: Esqueleto de `ms-agrotrack-users` y esquema

**Files:**
- Create: `ms-agrotrack-users/` (copiando `mvnw`, `mvnw.cmd`, `.mvn/`, `.gitignore`, `.gitattributes`, `Dockerfile` de catalog)
- Create: `ms-agrotrack-users/pom.xml`
- Create: `ms-agrotrack-users/src/main/java/cl/agrotrack/users/MsAgrotrackUsersApplication.java`
- Create: `.../users/config/SecurityConfig.java`, `.../users/config/JwtRolesConverter.java`, `.../users/config/RelojConfig.java`
- Create: `ms-agrotrack-users/src/main/resources/application.yml`, `application-local.yml`, `application-aws.yml`, `jwt/local-public.pem` (copia)
- Create: `ms-agrotrack-users/src/main/resources/db/migration/V1__users.sql`
- Create: `infra/local/init-postgres/02-users.sql`
- Test: `ms-agrotrack-users/src/test/java/cl/agrotrack/users/soporte/PostgresIT.java`, `.../users/infraestructura/persistencia/UsersSchemaIT.java`

**Interfaces:**
- Produces: tablas `usuario` y `perfil_productor`, secuencia `seq_usuario`; clase base `PostgresIT`; bean `Clock`.

- [ ] **Step 1: Copiar el andamiaje de catalog**

```bash
cd /c/Users/deint/Desktop/AgroTrack
mkdir -p ms-agrotrack-users/src/main/java/cl/agrotrack/users/config ms-agrotrack-users/src/main/resources/db/migration ms-agrotrack-users/src/main/resources/jwt ms-agrotrack-users/src/test/java/cl/agrotrack/users/soporte ms-agrotrack-users/src/test/java/cl/agrotrack/users/infraestructura/persistencia
cp -r ms-agrotrack-catalog/.mvn ms-agrotrack-catalog/mvnw ms-agrotrack-catalog/mvnw.cmd ms-agrotrack-catalog/.gitignore ms-agrotrack-catalog/.gitattributes ms-agrotrack-catalog/Dockerfile ms-agrotrack-users/
cp ms-agrotrack-catalog/src/main/resources/jwt/local-public.pem ms-agrotrack-users/src/main/resources/jwt/
sed 's/package cl.agrotrack.catalog.config;/package cl.agrotrack.users.config;/' ms-agrotrack-catalog/src/main/java/cl/agrotrack/catalog/config/JwtRolesConverter.java > ms-agrotrack-users/src/main/java/cl/agrotrack/users/config/JwtRolesConverter.java
```

- [ ] **Step 2: `pom.xml`** — igual al de catalog **sin** `spring-kafka`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
	xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
	<modelVersion>4.0.0</modelVersion>
	<parent>
		<groupId>org.springframework.boot</groupId>
		<artifactId>spring-boot-starter-parent</artifactId>
		<version>3.5.16</version>
		<relativePath/>
	</parent>
	<groupId>cl.agrotrack</groupId>
	<artifactId>ms-agrotrack-users</artifactId>
	<version>0.0.1-SNAPSHOT</version>
	<name>ms-agrotrack-users</name>
	<description>AgroTrack users service: registro de usuarios, aprobacion y ficha del productor</description>

	<properties>
		<java.version>21</java.version>
	</properties>

	<dependencies>
		<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
		<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
		<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
		<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-oauth2-resource-server</artifactId></dependency>
		<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
		<dependency><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId></dependency>
		<dependency><groupId>org.flywaydb</groupId><artifactId>flyway-database-postgresql</artifactId></dependency>
		<dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
		<dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><optional>true</optional></dependency>

		<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
		<dependency><groupId>org.springframework.security</groupId><artifactId>spring-security-test</artifactId><scope>test</scope></dependency>
		<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-testcontainers</artifactId><scope>test</scope></dependency>
		<dependency><groupId>org.testcontainers</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
		<dependency><groupId>org.testcontainers</groupId><artifactId>postgresql</artifactId><scope>test</scope></dependency>
	</dependencies>

	<build>
		<plugins>
			<plugin>
				<!-- Los *IT corren con los unitarios, igual que en catalog. -->
				<groupId>org.apache.maven.plugins</groupId>
				<artifactId>maven-surefire-plugin</artifactId>
				<configuration>
					<includes>
						<include>**/*Test.java</include>
						<include>**/*Tests.java</include>
						<include>**/*IT.java</include>
					</includes>
				</configuration>
			</plugin>
			<plugin>
				<groupId>org.springframework.boot</groupId>
				<artifactId>spring-boot-maven-plugin</artifactId>
				<configuration>
					<excludes>
						<exclude><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId></exclude>
					</excludes>
				</configuration>
			</plugin>
		</plugins>
	</build>
</project>
```

- [ ] **Step 3: Aplicación, seguridad y reloj**

`MsAgrotrackUsersApplication.java`:
```java
package cl.agrotrack.users;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MsAgrotrackUsersApplication {

    public static void main(String[] args) {
        SpringApplication.run(MsAgrotrackUsersApplication.class, args);
    }
}
```

`config/SecurityConfig.java`:
```java
package cl.agrotrack.users.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Resource server igual al de los demas servicios. La autorizacion fina
 * va con @PreAuthorize en el controlador.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new JwtRolesConverter())))
                .build();
    }
}
```

`config/RelojConfig.java`:
```java
package cl.agrotrack.users.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Reloj inyectable: las fechas de ingreso y aprobacion no dependen de Instant.now() suelto. */
@Configuration
public class RelojConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
```

- [ ] **Step 4: Configuración**

`application.yml`:
```yaml
spring:
  application:
    name: ms-agrotrack-users
  profiles:
    default: local
  jpa:
    open-in-view: false
    hibernate:
      # El esquema lo gobierna Flyway.
      ddl-auto: validate
  flyway:
    enabled: true
    locations: classpath:db/migration

server:
  port: ${SERVER_PORT:8089}

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      probes:
        enabled: true
```

`application-local.yml`:
```yaml
# Desarrollo en el PC: infra/local/compose.yml levantado.
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/agro_users
    username: agro_users
    password: agro_users
  security:
    oauth2:
      resourceserver:
        jwt:
          # Tokens emitidos con infra/local/jwt/mint.mjs
          public-key-location: classpath:jwt/local-public.pem
```

`application-aws.yml`:
```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${AZURE_ISSUER_URI}
          # v1: aud = api://<clientId>; v2 (requestedAccessTokenVersion=2): aud = <clientId>. Se aceptan ambos.
          audiences: api://${AZURE_API_CLIENT_ID},${AZURE_API_CLIENT_ID}
```

- [ ] **Step 5: Escribir el test de esquema (falla: no hay migración)**

`src/test/java/cl/agrotrack/users/soporte/PostgresIT.java`:
```java
package cl.agrotrack.users.soporte;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/** PostgreSQL real en Docker, compartido por todas las clases *IT de la JVM. */
@SpringBootTest
public abstract class PostgresIT {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("agro_users")
            .withUsername("agro_users")
            .withPassword("agro_users");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
```

`src/test/java/cl/agrotrack/users/infraestructura/persistencia/UsersSchemaIT.java`:
```java
package cl.agrotrack.users.infraestructura.persistencia;

import cl.agrotrack.users.soporte.PostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsersSchemaIT extends PostgresIT {

    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void limpiar() {
        jdbc.update("DELETE FROM perfil_productor");
        jdbc.update("DELETE FROM usuario");
    }

    private long insertarUsuario(String oid) {
        Long id = jdbc.queryForObject("SELECT nextval('seq_usuario')", Long.class);
        jdbc.update("""
                INSERT INTO usuario (id, azure_oid, email, nombre, estado, primer_ingreso, ultimo_ingreso)
                VALUES (?, ?, 'x@y.cl', 'X', 'PENDIENTE', now(), now())""", id, oid);
        return id;
    }

    @Test
    @DisplayName("Flyway crea usuario y perfil_productor")
    void tablas() {
        Integer tablas = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name IN ('usuario','perfil_productor')", Integer.class);
        assertThat(tablas).isEqualTo(2);
    }

    @Test
    @DisplayName("La base rechaza un estado que no existe")
    void checkEstado() {
        long id = insertarUsuario("oid-1");
        assertThatThrownBy(() -> jdbc.update("UPDATE usuario SET estado = 'BORRADO' WHERE id = ?", id))
                .hasMessageContaining("ck_usuario_estado");
    }

    @Test
    @DisplayName("El oid de Azure es unico")
    void oidUnico() {
        insertarUsuario("oid-dup");
        assertThatThrownBy(() -> insertarUsuario("oid-dup")).hasMessageContaining("uk_usuario_azure_oid");
    }

    @Test
    @DisplayName("Dos fichas no pueden compartir RUT")
    void rutUnico() {
        long a = insertarUsuario("oid-a");
        long b = insertarUsuario("oid-b");
        jdbc.update("INSERT INTO perfil_productor (usuario_id, rut, razon_social) VALUES (?, '12345678-9', 'A')", a);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO perfil_productor (usuario_id, rut, razon_social) VALUES (?, '12345678-9', 'B')", b))
                .hasMessageContaining("uk_perfil_rut");
    }
}
```

- [ ] **Step 6: Correr y ver que falla**

Run: `cd ms-agrotrack-users && ./mvnw -q test -Dtest=UsersSchemaIT`
Expected: FAIL (tabla `usuario` no existe / contexto no arranca).

- [ ] **Step 7: Migración `V1__users.sql`**

```sql
-- Base agro_users (PostgreSQL). Ver docs/06-modelo-de-datos.md.

CREATE SEQUENCE seq_usuario START 1 INCREMENT 1;

CREATE TABLE usuario (
    id                  BIGINT          NOT NULL,
    -- El enlace con Azure AD: el claim oid del token
    azure_oid           VARCHAR(50)     NOT NULL,
    email               VARCHAR(200),
    nombre              VARCHAR(200),
    estado              VARCHAR(20)     NOT NULL,
    -- Informativo: la autorizacion usa el token de ahora, no esta columna
    rol_ultimo_token    VARCHAR(20),
    motivo              VARCHAR(500),
    primer_ingreso      TIMESTAMPTZ     NOT NULL,
    ultimo_ingreso      TIMESTAMPTZ     NOT NULL,
    aprobado_por        VARCHAR(50),
    aprobado_en         TIMESTAMPTZ,
    version             BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT pk_usuario           PRIMARY KEY (id),
    CONSTRAINT uk_usuario_azure_oid UNIQUE (azure_oid),
    CONSTRAINT ck_usuario_estado    CHECK (estado IN ('PENDIENTE', 'ACTIVO', 'RECHAZADO', 'INACTIVO'))
);

CREATE INDEX ix_usuario_estado ON usuario (estado);

-- Solo los productores tienen ficha: tabla aparte, no columnas anulables en usuario.
CREATE TABLE perfil_productor (
    usuario_id          BIGINT          NOT NULL,
    rut                 VARCHAR(15)     NOT NULL,
    razon_social        VARCHAR(200)    NOT NULL,
    telefono            VARCHAR(30),
    direccion           VARCHAR(300),
    -- Sin FK: la bodega vive en agro_catalog
    bodega_habitual_id  BIGINT,
    CONSTRAINT pk_perfil_productor PRIMARY KEY (usuario_id),
    CONSTRAINT fk_perfil_usuario   FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    CONSTRAINT uk_perfil_rut       UNIQUE (rut)
);
```

- [ ] **Step 8: Base y rol en Postgres** — `infra/local/init-postgres/02-users.sql`. Idempotente a propósito: en AWS el volumen ya existe y el init de Docker no vuelve a correr, así que el mismo archivo se aplica a mano (Task 13).

```sql
-- Base de ms-agrotrack-users (D1: una base y un rol por servicio).
-- Idempotente: se puede volver a ejecutar sobre una instancia que ya tiene
-- datos (psql -f), que es lo que pasa en AWS con el volumen existente.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'agro_users') THEN
        CREATE ROLE agro_users LOGIN PASSWORD 'agro_users';
    END IF;
END
$$;

SELECT 'CREATE DATABASE agro_users OWNER agro_users'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'agro_users')\gexec
```

Aplicarlo al Postgres local, que ya tiene volumen:
```bash
cd /c/Users/deint/Desktop/AgroTrack/infra
docker exec -i at-postgres psql -U agrotrack -d postgres < local/init-postgres/02-users.sql
```
Expected: `DO` y `CREATE DATABASE` (o nada la segunda vez).

- [ ] **Step 9: Correr y ver que pasa**

Run: `./mvnw -q test -Dtest=UsersSchemaIT`
Expected: 4 tests PASS.

- [ ] **Step 10: Commit**

```bash
cd /c/Users/deint/Desktop/AgroTrack
git add ms-agrotrack-users infra/local/init-postgres/02-users.sql
git commit -m "feat(users): esqueleto del servicio y esquema agro_users"
```

---

### Task 2: Máquina de estados del usuario (dominio puro)

**Files:**
- Create: `ms-agrotrack-users/src/main/java/cl/agrotrack/users/dominio/EstadoUsuario.java`
- Create: `.../users/dominio/MotivoRechazoCambio.java`
- Create: `.../users/dominio/CambioEstadoInvalidoException.java`
- Create: `.../users/dominio/MaquinaEstadosUsuario.java`
- Test: `ms-agrotrack-users/src/test/java/cl/agrotrack/users/dominio/MaquinaEstadosUsuarioTest.java`

**Interfaces:**
- Produces:
  - `enum EstadoUsuario { PENDIENTE, ACTIVO, RECHAZADO, INACTIVO }`
  - `enum MotivoRechazoCambio { TRANSICION_NO_PERMITIDA, MOTIVO_OBLIGATORIO, AUTO_DESACTIVACION }`
  - `class CambioEstadoInvalidoException extends RuntimeException { MotivoRechazoCambio motivo() }`
  - `static EstadoUsuario MaquinaEstadosUsuario.estadoInicial(boolean tokenTraeAdmin, boolean hayUsuariosActivos)`
  - `static void MaquinaEstadosUsuario.validarCambio(EstadoUsuario actual, EstadoUsuario destino, String motivo, boolean esElMismoUsuario)` — lanza `CambioEstadoInvalidoException`

- [ ] **Step 1: Escribir los tests**

```java
package cl.agrotrack.users.dominio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static cl.agrotrack.users.dominio.EstadoUsuario.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Especificacion ejecutable del ciclo de vida de una cuenta. Sin Spring ni base. */
class MaquinaEstadosUsuarioTest {

    @Nested
    @DisplayName("Estado al primer ingreso")
    class EstadoInicial {

        @Test
        @DisplayName("Sistema sin activos y token ADMIN: nace ACTIVO (primer admin)")
        void primerAdmin() {
            assertThat(MaquinaEstadosUsuario.estadoInicial(true, false)).isEqualTo(ACTIVO);
        }

        @Test
        @DisplayName("Ya hay un activo: el segundo admin nace PENDIENTE")
        void segundoAdmin() {
            assertThat(MaquinaEstadosUsuario.estadoInicial(true, true)).isEqualTo(PENDIENTE);
        }

        @Test
        @DisplayName("Sin ADMIN en el token nunca se auto-aprueba, aunque el sistema este vacio")
        void noAdminVacio() {
            assertThat(MaquinaEstadosUsuario.estadoInicial(false, false)).isEqualTo(PENDIENTE);
        }
    }

    @Nested
    @DisplayName("Cambios permitidos")
    class Permitidos {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({"PENDIENTE,ACTIVO", "INACTIVO,ACTIVO", "RECHAZADO,ACTIVO"})
        @DisplayName("Aprobar, reactivar y reconsiderar no exigen motivo")
        void sinMotivo(EstadoUsuario desde, EstadoUsuario hacia) {
            assertThatCode(() -> MaquinaEstadosUsuario.validarCambio(desde, hacia, null, false)).doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({"PENDIENTE,RECHAZADO", "ACTIVO,INACTIVO"})
        @DisplayName("Rechazar y desactivar con motivo")
        void conMotivo(EstadoUsuario desde, EstadoUsuario hacia) {
            assertThatCode(() -> MaquinaEstadosUsuario.validarCambio(desde, hacia, "no es productor", false)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Cambios rechazados")
    class Rechazados {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({"PENDIENTE,INACTIVO", "ACTIVO,PENDIENTE", "ACTIVO,RECHAZADO", "RECHAZADO,INACTIVO",
                "INACTIVO,RECHAZADO", "ACTIVO,ACTIVO", "PENDIENTE,PENDIENTE"})
        @DisplayName("Transiciones fuera de la tabla")
        void fueraDeTabla(EstadoUsuario desde, EstadoUsuario hacia) {
            assertThatThrownBy(() -> MaquinaEstadosUsuario.validarCambio(desde, hacia, "motivo", false))
                    .isInstanceOfSatisfying(CambioEstadoInvalidoException.class,
                            e -> assertThat(e.motivo()).isEqualTo(MotivoRechazoCambio.TRANSICION_NO_PERMITIDA));
        }

        @ParameterizedTest(name = "motivo=''{0}''")
        @CsvSource(value = {"NULL", "''", "'   '"}, nullValues = "NULL")
        @DisplayName("Rechazar sin motivo")
        void rechazarSinMotivo(String motivo) {
            assertThatThrownBy(() -> MaquinaEstadosUsuario.validarCambio(PENDIENTE, RECHAZADO, motivo, false))
                    .isInstanceOfSatisfying(CambioEstadoInvalidoException.class,
                            e -> assertThat(e.motivo()).isEqualTo(MotivoRechazoCambio.MOTIVO_OBLIGATORIO));
        }

        @Test
        @DisplayName("Un admin no puede desactivarse a si mismo, aunque de motivo")
        void autoDesactivacion() {
            assertThatThrownBy(() -> MaquinaEstadosUsuario.validarCambio(ACTIVO, INACTIVO, "me voy", true))
                    .isInstanceOfSatisfying(CambioEstadoInvalidoException.class,
                            e -> assertThat(e.motivo()).isEqualTo(MotivoRechazoCambio.AUTO_DESACTIVACION));
        }
    }
}
```

- [ ] **Step 2: Correr y ver que falla**

Run: `./mvnw -q test -Dtest=MaquinaEstadosUsuarioTest`
Expected: FAIL de compilación (`EstadoUsuario` no existe).

- [ ] **Step 3: Implementar**

`EstadoUsuario.java`:
```java
package cl.agrotrack.users.dominio;

/** Ciclo de vida de una cuenta en AgroTrack. Solo ACTIVO puede usar el sistema. */
public enum EstadoUsuario {
    PENDIENTE,
    ACTIVO,
    RECHAZADO,
    INACTIVO
}
```

`MotivoRechazoCambio.java`:
```java
package cl.agrotrack.users.dominio;

/** Por que no procede un cambio de estado. Viaja como "codigo" en el problem+json. */
public enum MotivoRechazoCambio {
    TRANSICION_NO_PERMITIDA,
    MOTIVO_OBLIGATORIO,
    AUTO_DESACTIVACION
}
```

`CambioEstadoInvalidoException.java`:
```java
package cl.agrotrack.users.dominio;

public class CambioEstadoInvalidoException extends RuntimeException {

    private final MotivoRechazoCambio motivo;

    public CambioEstadoInvalidoException(MotivoRechazoCambio motivo, String mensaje) {
        super(mensaje);
        this.motivo = motivo;
    }

    public MotivoRechazoCambio motivo() {
        return motivo;
    }
}
```

`MaquinaEstadosUsuario.java`:
```java
package cl.agrotrack.users.dominio;

import java.util.Map;
import java.util.Set;

import static cl.agrotrack.users.dominio.EstadoUsuario.*;

/**
 * Reglas del ciclo de vida de una cuenta. Clase pura, igual que la maquina
 * de estados de la entrega (D6): sin Spring, sin JPA, sin red.
 */
public final class MaquinaEstadosUsuario {

    private static final Map<EstadoUsuario, Set<EstadoUsuario>> PERMITIDAS = Map.of(
            PENDIENTE, Set.of(ACTIVO, RECHAZADO),
            ACTIVO, Set.of(INACTIVO),
            INACTIVO, Set.of(ACTIVO),
            RECHAZADO, Set.of(ACTIVO));

    private MaquinaEstadosUsuario() {
    }

    /**
     * Si todos nacieran PENDIENTE nadie podria aprobar a nadie y el sistema
     * naceria bloqueado. Por eso, mientras no haya ningun ACTIVO, el primero
     * que entra con ADMIN en el token queda activo. Solo puede pasar una vez.
     */
    public static EstadoUsuario estadoInicial(boolean tokenTraeAdmin, boolean hayUsuariosActivos) {
        return tokenTraeAdmin && !hayUsuariosActivos ? ACTIVO : PENDIENTE;
    }

    /**
     * El orden importa: primero si la transicion existe, despues la
     * auto-desactivacion y al final el motivo, para que el mensaje apunte a
     * lo que de verdad impide el cambio.
     */
    public static void validarCambio(EstadoUsuario actual, EstadoUsuario destino, String motivo, boolean esElMismoUsuario) {
        if (!PERMITIDAS.get(actual).contains(destino)) {
            throw new CambioEstadoInvalidoException(MotivoRechazoCambio.TRANSICION_NO_PERMITIDA,
                    "No se puede pasar de " + actual + " a " + destino);
        }
        if (destino == INACTIVO && esElMismoUsuario) {
            // Si es el unico admin, dejaria el sistema sin quien apruebe.
            throw new CambioEstadoInvalidoException(MotivoRechazoCambio.AUTO_DESACTIVACION,
                    "No puedes desactivar tu propia cuenta");
        }
        if ((destino == RECHAZADO || destino == INACTIVO) && (motivo == null || motivo.isBlank())) {
            throw new CambioEstadoInvalidoException(MotivoRechazoCambio.MOTIVO_OBLIGATORIO,
                    "Indica el motivo para dejar la cuenta en " + destino);
        }
    }
}
```

- [ ] **Step 4: Correr y ver que pasa**

Run: `./mvnw -q test -Dtest=MaquinaEstadosUsuarioTest`
Expected: PASS (18 casos).

- [ ] **Step 5: Commit**

```bash
git add ms-agrotrack-users/src
git commit -m "feat(users): maquina de estados de la cuenta y regla del primer admin"
```

---
### Task 3: Persistencia y `sincronizar` (registro en el primer ingreso)

**Files:**
- Create: `ms-agrotrack-users/src/main/java/cl/agrotrack/users/infraestructura/persistencia/Usuario.java`
- Create: `.../persistencia/PerfilProductor.java`, `.../persistencia/UsuarioRepository.java`, `.../persistencia/PerfilProductorRepository.java`
- Create: `.../users/aplicacion/IdentidadToken.java`, `.../aplicacion/Dtos.java`, `.../aplicacion/RecursoNoEncontradoException.java`, `.../aplicacion/UsuarioService.java`
- Test: `ms-agrotrack-users/src/test/java/cl/agrotrack/users/aplicacion/SincronizarIT.java`

**Interfaces:**
- Consumes: `EstadoUsuario`, `MaquinaEstadosUsuario.estadoInicial/validarCambio` (Task 2); `PostgresIT`, `Clock` (Task 1).
- Produces:
  - `record IdentidadToken(String oid, String email, String nombre, List<String> roles)` con `static IdentidadToken desde(Jwt)`, `boolean esAdmin()`, `String rolPrincipal()`
  - `Dtos.UsuarioResponse(Long id, String azureOid, String email, String nombre, EstadoUsuario estado, String rolUltimoToken, String motivo, Instant primerIngreso, Instant ultimoIngreso, String aprobadoPor, Instant aprobadoEn, PerfilResponse perfil)`
  - `Dtos.PerfilResponse(String rut, String razonSocial, String telefono, String direccion, Long bodegaHabitualId)`
  - `Dtos.CambioEstadoRequest(EstadoUsuario estado, String motivo)`, `Dtos.PerfilRequest(String rut, String razonSocial, String telefono, String direccion, Long bodegaHabitualId)`
  - `UsuarioService.sincronizar(IdentidadToken): UsuarioResponse`
  - `RecursoNoEncontradoException(String recurso, Object id)`

- [ ] **Step 1: Escribir los tests**

```java
package cl.agrotrack.users.aplicacion;

import cl.agrotrack.users.dominio.EstadoUsuario;
import cl.agrotrack.users.soporte.PostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class SincronizarIT extends PostgresIT {

    @Autowired UsuarioService servicio;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void limpiar() {
        jdbc.update("DELETE FROM perfil_productor");
        jdbc.update("DELETE FROM usuario");
    }

    private static IdentidadToken token(String oid, String... roles) {
        return new IdentidadToken(oid, oid + "@agrotrack.cl", "Nombre " + oid, List.of(roles));
    }

    @Test
    @DisplayName("Sincronizar dos veces deja UN registro y solo mueve ultimo_ingreso")
    void idempotente() {
        var primera = servicio.sincronizar(token("oid-1", "CLIENTE"));
        // Se envejece el ingreso para que el segundo sea medible sin depender del reloj
        jdbc.update("UPDATE usuario SET ultimo_ingreso = ultimo_ingreso - interval '1 hour'");

        var segunda = servicio.sincronizar(token("oid-1", "CLIENTE"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usuario", Integer.class)).isEqualTo(1);
        assertThat(segunda.id()).isEqualTo(primera.id());
        assertThat(segunda.primerIngreso()).isEqualTo(primera.primerIngreso());
        assertThat(segunda.ultimoIngreso()).isAfter(segunda.primerIngreso());
        assertThat(segunda.estado()).isEqualTo(EstadoUsuario.PENDIENTE);
    }

    @Test
    @DisplayName("Actualiza nombre, correo y rol del token en cada ingreso")
    void actualizaDatos() {
        servicio.sincronizar(token("oid-2", "CLIENTE"));
        var r = servicio.sincronizar(new IdentidadToken("oid-2", "nuevo@agrotrack.cl", "Nombre Nuevo", List.of("OPERADOR")));

        assertThat(r.email()).isEqualTo("nuevo@agrotrack.cl");
        assertThat(r.nombre()).isEqualTo("Nombre Nuevo");
        assertThat(r.rolUltimoToken()).isEqualTo("OPERADOR");
    }

    @Test
    @DisplayName("El primer admin nace ACTIVO aprobado por 'sistema'; el segundo nace PENDIENTE")
    void primerAdminAcotado() {
        var primero = servicio.sincronizar(token("admin-1", "ADMIN"));
        var segundo = servicio.sincronizar(token("admin-2", "ADMIN"));

        assertThat(primero.estado()).isEqualTo(EstadoUsuario.ACTIVO);
        assertThat(primero.aprobadoPor()).isEqualTo("sistema");
        assertThat(primero.aprobadoEn()).isNotNull();
        assertThat(segundo.estado()).isEqualTo(EstadoUsuario.PENDIENTE);
    }

    @Test
    @DisplayName("Sin ADMIN en el token nunca se auto-aprueba, aunque la tabla este vacia")
    void noAdminNuncaSeAutoAprueba() {
        assertThat(servicio.sincronizar(token("cli-1", "CLIENTE")).estado()).isEqualTo(EstadoUsuario.PENDIENTE);
        assertThat(servicio.sincronizar(token("sin-rol")).estado()).isEqualTo(EstadoUsuario.PENDIENTE);
    }

    @Test
    @DisplayName("Ocho admins entrando a la vez con el sistema vacio: exactamente uno queda ACTIVO")
    void primerAdminConcurrente() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Object>> tareas = IntStream.range(0, 8)
                    .<Callable<Object>>mapToObj(i -> () -> servicio.sincronizar(token("admin-c" + i, "ADMIN")))
                    .toList();
            for (var f : pool.invokeAll(tareas)) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usuario WHERE estado = 'ACTIVO'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usuario", Integer.class)).isEqualTo(8);
    }
}
```

- [ ] **Step 2: Correr y ver que falla**

Run: `./mvnw -q test -Dtest=SincronizarIT`
Expected: FAIL de compilación (`UsuarioService` no existe).

- [ ] **Step 3: Entidades y repositorios**

`Usuario.java`:
```java
package cl.agrotrack.users.infraestructura.persistencia;

import cl.agrotrack.users.dominio.EstadoUsuario;
import cl.agrotrack.users.dominio.MaquinaEstadosUsuario;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "usuario")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Usuario {

    /** Quien firma la aprobacion del primer admin. */
    public static final String APROBADOR_SISTEMA = "sistema";

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_usuario")
    @SequenceGenerator(name = "seq_usuario", sequenceName = "seq_usuario", allocationSize = 1)
    private Long id;

    @Column(name = "azure_oid", nullable = false, length = 50)
    private String azureOid;

    @Column(length = 200)
    private String email;

    @Column(length = 200)
    private String nombre;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstadoUsuario estado;

    @Column(name = "rol_ultimo_token", length = 20)
    private String rolUltimoToken;

    @Column(length = 500)
    private String motivo;

    @Column(name = "primer_ingreso", nullable = false)
    private Instant primerIngreso;

    @Column(name = "ultimo_ingreso", nullable = false)
    private Instant ultimoIngreso;

    @Column(name = "aprobado_por", length = 50)
    private String aprobadoPor;

    @Column(name = "aprobado_en")
    private Instant aprobadoEn;

    /** Dos admins aprobando y rechazando a la vez: gana uno, el otro recibe 409. */
    @Version
    private Long version;

    public static Usuario registrar(String azureOid, String email, String nombre, String rol,
                                    EstadoUsuario estadoInicial, Instant ahora) {
        Usuario u = new Usuario();
        u.azureOid = azureOid;
        u.email = email;
        u.nombre = nombre;
        u.rolUltimoToken = rol;
        u.estado = estadoInicial;
        u.primerIngreso = ahora;
        u.ultimoIngreso = ahora;
        if (estadoInicial == EstadoUsuario.ACTIVO) {
            u.aprobadoPor = APROBADOR_SISTEMA;
            u.aprobadoEn = ahora;
        }
        return u;
    }

    /** Cada ingreso refresca lo que viene del token. El estado no se toca. */
    public void registrarIngreso(String email, String nombre, String rol, Instant ahora) {
        this.email = email;
        this.nombre = nombre;
        this.rolUltimoToken = rol;
        this.ultimoIngreso = ahora;
    }

    public void cambiarEstado(EstadoUsuario destino, String motivo, String adminOid, Instant ahora) {
        MaquinaEstadosUsuario.validarCambio(estado, destino, motivo, azureOid.equals(adminOid));
        this.estado = destino;
        if (destino == EstadoUsuario.ACTIVO) {
            this.aprobadoPor = adminOid;
            this.aprobadoEn = ahora;
            this.motivo = null;
        } else {
            this.motivo = motivo.trim();
        }
    }
}
```

`PerfilProductor.java`:
```java
package cl.agrotrack.users.infraestructura.persistencia;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "perfil_productor")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PerfilProductor {

    /** Misma clave que el usuario: una ficha por persona. */
    @Id
    @Column(name = "usuario_id")
    private Long usuarioId;

    @Column(nullable = false, length = 15)
    private String rut;

    @Column(name = "razon_social", nullable = false, length = 200)
    private String razonSocial;

    @Column(length = 30)
    private String telefono;

    @Column(length = 300)
    private String direccion;

    @Column(name = "bodega_habitual_id")
    private Long bodegaHabitualId;

    public PerfilProductor(Long usuarioId) {
        this.usuarioId = usuarioId;
    }

    public void actualizar(String rut, String razonSocial, String telefono, String direccion, Long bodegaHabitualId) {
        this.rut = rut.trim().toUpperCase();
        this.razonSocial = razonSocial.trim();
        this.telefono = telefono;
        this.direccion = direccion;
        this.bodegaHabitualId = bodegaHabitualId;
    }
}
```

`UsuarioRepository.java`:
```java
package cl.agrotrack.users.infraestructura.persistencia;

import cl.agrotrack.users.dominio.EstadoUsuario;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    Optional<Usuario> findByAzureOid(String azureOid);

    boolean existsByEstado(EstadoUsuario estado);

    List<Usuario> findAllByOrderByPrimerIngresoDesc();

    List<Usuario> findByEstadoOrderByPrimerIngresoDesc(EstadoUsuario estado);
}
```

`PerfilProductorRepository.java`:
```java
package cl.agrotrack.users.infraestructura.persistencia;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PerfilProductorRepository extends JpaRepository<PerfilProductor, Long> {

    Optional<PerfilProductor> findByRut(String rut);
}
```

- [ ] **Step 4: Aplicación**

`IdentidadToken.java`:
```java
package cl.agrotrack.users.aplicacion;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/** Lo que este servicio necesita del token de Azure AD (o de mint.mjs en local). */
public record IdentidadToken(String oid, String email, String nombre, List<String> roles) {

    /** Si el token trae varios roles se muestra el mas privilegiado, igual que en deliveries. */
    private static final List<String> PRIORIDAD = List.of("ADMIN", "OPERADOR", "CLIENTE", "AUDITOR");

    public IdentidadToken {
        roles = roles == null ? List.of() : roles.stream().map(String::toUpperCase).toList();
    }

    public static IdentidadToken desde(Jwt jwt) {
        String oid = jwt.getClaimAsString("oid");
        String email = jwt.getClaimAsString("preferred_username");
        return new IdentidadToken(
                oid != null ? oid : jwt.getSubject(),
                email != null ? email : jwt.getClaimAsString("email"),
                jwt.getClaimAsString("name"),
                jwt.getClaimAsStringList("roles"));
    }

    public boolean esAdmin() {
        return roles.contains("ADMIN");
    }

    public String rolPrincipal() {
        return PRIORIDAD.stream().filter(roles::contains).findFirst().orElse(null);
    }
}
```

`RecursoNoEncontradoException.java`:
```java
package cl.agrotrack.users.aplicacion;

public class RecursoNoEncontradoException extends RuntimeException {

    public RecursoNoEncontradoException(String recurso, Object id) {
        super(recurso + " " + id + " no existe");
    }
}
```

`Dtos.java`:
```java
package cl.agrotrack.users.aplicacion;

import cl.agrotrack.users.dominio.EstadoUsuario;
import cl.agrotrack.users.infraestructura.persistencia.PerfilProductor;
import cl.agrotrack.users.infraestructura.persistencia.Usuario;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class Dtos {

    private Dtos() {
    }

    public record UsuarioResponse(
            Long id, String azureOid, String email, String nombre, EstadoUsuario estado,
            String rolUltimoToken, String motivo, Instant primerIngreso, Instant ultimoIngreso,
            String aprobadoPor, Instant aprobadoEn, PerfilResponse perfil) {

        public static UsuarioResponse de(Usuario u, PerfilProductor perfil) {
            return new UsuarioResponse(u.getId(), u.getAzureOid(), u.getEmail(), u.getNombre(), u.getEstado(),
                    u.getRolUltimoToken(), u.getMotivo(), u.getPrimerIngreso(), u.getUltimoIngreso(),
                    u.getAprobadoPor(), u.getAprobadoEn(), perfil == null ? null : PerfilResponse.de(perfil));
        }
    }

    public record PerfilResponse(String rut, String razonSocial, String telefono, String direccion, Long bodegaHabitualId) {

        public static PerfilResponse de(PerfilProductor p) {
            return new PerfilResponse(p.getRut(), p.getRazonSocial(), p.getTelefono(), p.getDireccion(), p.getBodegaHabitualId());
        }
    }

    public record CambioEstadoRequest(@NotNull EstadoUsuario estado, @Size(max = 500) String motivo) {
    }

    public record PerfilRequest(
            @NotBlank @Pattern(regexp = "^\\d{7,8}-[\\dkK]$", message = "RUT con guion, sin puntos: 12345678-9") String rut,
            @NotBlank @Size(max = 200) String razonSocial,
            @Size(max = 30) String telefono,
            @Size(max = 300) String direccion,
            Long bodegaHabitualId) {
    }
}
```

`UsuarioService.java` (solo `sincronizar` en esta tarea; Task 4 añade el resto):
```java
package cl.agrotrack.users.aplicacion;

import cl.agrotrack.users.aplicacion.Dtos.UsuarioResponse;
import cl.agrotrack.users.dominio.EstadoUsuario;
import cl.agrotrack.users.dominio.MaquinaEstadosUsuario;
import cl.agrotrack.users.infraestructura.persistencia.PerfilProductorRepository;
import cl.agrotrack.users.infraestructura.persistencia.Usuario;
import cl.agrotrack.users.infraestructura.persistencia.UsuarioRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Service
@Transactional
public class UsuarioService {

    /** Clave del advisory lock que serializa las altas. Arbitraria, pero fija. */
    static final long BLOQUEO_ALTAS = 7_001L;

    private final UsuarioRepository usuarios;
    private final PerfilProductorRepository perfiles;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public UsuarioService(UsuarioRepository usuarios, PerfilProductorRepository perfiles, JdbcTemplate jdbc, Clock clock) {
        this.usuarios = usuarios;
        this.perfiles = perfiles;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Alta o actualizacion desde los claims. Idempotente: llamarlo cien veces
     * deja el mismo registro y solo mueve ultimo_ingreso.
     */
    public UsuarioResponse sincronizar(IdentidadToken id) {
        Instant ahora = clock.instant();
        Optional<Usuario> existente = usuarios.findByAzureOid(id.oid());
        if (existente.isEmpty()) {
            // Sin este bloqueo, dos admins entrando a la vez con el sistema vacio
            // verian los dos "no hay activos" y quedarian los dos aprobados: la
            // excepcion del primer admin dejaria de ser unica. Se libera solo
            // al terminar la transaccion.
            jdbc.execute("SELECT pg_advisory_xact_lock(" + BLOQUEO_ALTAS + ")");
            existente = usuarios.findByAzureOid(id.oid());
        }
        Usuario u;
        if (existente.isPresent()) {
            u = existente.get();
            u.registrarIngreso(id.email(), id.nombre(), id.rolPrincipal(), ahora);
        } else {
            EstadoUsuario inicial = MaquinaEstadosUsuario.estadoInicial(id.esAdmin(), usuarios.existsByEstado(EstadoUsuario.ACTIVO));
            u = usuarios.save(Usuario.registrar(id.oid(), id.email(), id.nombre(), id.rolPrincipal(), inicial, ahora));
        }
        return UsuarioResponse.de(u, perfiles.findById(u.getId()).orElse(null));
    }
}
```

- [ ] **Step 5: Correr y ver que pasa**

Run: `./mvnw -q test -Dtest=SincronizarIT`
Expected: 5 tests PASS. Si `primerAdminConcurrente` falla con duplicado en `uk_usuario_azure_oid` o con dos ACTIVO, revisa que el `findByAzureOid` se repita **después** del bloqueo y que `existsByEstado` se consulte también después.

- [ ] **Step 6: Commit**

```bash
git add ms-agrotrack-users/src
git commit -m "feat(users): registro en el primer ingreso, idempotente y con primer admin unico"
```

---

### Task 4: Listar, detalle, cambiar estado y ficha del productor

**Files:**
- Modify: `ms-agrotrack-users/src/main/java/cl/agrotrack/users/aplicacion/UsuarioService.java`
- Create: `.../users/aplicacion/RutDuplicadoException.java`
- Test: `ms-agrotrack-users/src/test/java/cl/agrotrack/users/aplicacion/AdministracionIT.java`

**Interfaces:**
- Consumes: todo lo de Task 3.
- Produces (en `UsuarioService`):
  - `List<UsuarioResponse> listar(EstadoUsuario estadoONull)`
  - `UsuarioResponse obtener(Long id)`
  - `UsuarioResponse me(String oid)` — `RecursoNoEncontradoException` si nunca sincronizó
  - `UsuarioResponse cambiarEstado(Long id, CambioEstadoRequest req, String adminOid)`
  - `PerfilResponse actualizarPerfil(Long id, PerfilRequest req, IdentidadToken quien)` — `org.springframework.security.access.AccessDeniedException` si no es admin ni dueño; `RutDuplicadoException` si el RUT es de otra ficha

- [ ] **Step 1: Escribir los tests**

```java
package cl.agrotrack.users.aplicacion;

import cl.agrotrack.users.aplicacion.Dtos.CambioEstadoRequest;
import cl.agrotrack.users.aplicacion.Dtos.PerfilRequest;
import cl.agrotrack.users.dominio.CambioEstadoInvalidoException;
import cl.agrotrack.users.dominio.MotivoRechazoCambio;
import cl.agrotrack.users.soporte.PostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;

import static cl.agrotrack.users.dominio.EstadoUsuario.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdministracionIT extends PostgresIT {

    @Autowired UsuarioService servicio;
    @Autowired JdbcTemplate jdbc;

    IdentidadToken admin;
    IdentidadToken productor;
    Long idAdmin;
    Long idProductor;

    @BeforeEach
    void preparar() {
        jdbc.update("DELETE FROM perfil_productor");
        jdbc.update("DELETE FROM usuario");
        admin = new IdentidadToken("admin-oid", "admin@agrotrack.cl", "Admin", List.of("ADMIN"));
        productor = new IdentidadToken("prod-oid", "prod@agrotrack.cl", "Productor", List.of("CLIENTE"));
        idAdmin = servicio.sincronizar(admin).id();          // primer admin: ACTIVO
        idProductor = servicio.sincronizar(productor).id();  // PENDIENTE
    }

    @Test
    @DisplayName("Aprobar deja quien y cuando")
    void aprobar() {
        var r = servicio.cambiarEstado(idProductor, new CambioEstadoRequest(ACTIVO, null), "admin-oid");

        assertThat(r.estado()).isEqualTo(ACTIVO);
        assertThat(r.aprobadoPor()).isEqualTo("admin-oid");
        assertThat(r.aprobadoEn()).isNotNull();
    }

    @Test
    @DisplayName("Rechazar guarda el motivo; reconsiderar lo limpia")
    void rechazarYReconsiderar() {
        var rechazado = servicio.cambiarEstado(idProductor, new CambioEstadoRequest(RECHAZADO, "  RUT no coincide "), "admin-oid");
        assertThat(rechazado.motivo()).isEqualTo("RUT no coincide");

        var reconsiderado = servicio.cambiarEstado(idProductor, new CambioEstadoRequest(ACTIVO, null), "admin-oid");
        assertThat(reconsiderado.motivo()).isNull();
    }

    @Test
    @DisplayName("El admin no puede desactivarse a si mismo")
    void autoDesactivacion() {
        assertThatThrownBy(() -> servicio.cambiarEstado(idAdmin, new CambioEstadoRequest(INACTIVO, "prueba"), "admin-oid"))
                .isInstanceOfSatisfying(CambioEstadoInvalidoException.class,
                        e -> assertThat(e.motivo()).isEqualTo(MotivoRechazoCambio.AUTO_DESACTIVACION));
        assertThat(servicio.obtener(idAdmin).estado()).isEqualTo(ACTIVO);
    }

    @Test
    @DisplayName("Listar filtra por estado; sin filtro trae a todos")
    void listar() {
        assertThat(servicio.listar(PENDIENTE)).extracting(Dtos.UsuarioResponse::azureOid).containsExactly("prod-oid");
        assertThat(servicio.listar(null)).hasSize(2);
    }

    @Test
    @DisplayName("me de alguien que nunca entro: no existe")
    void meInexistente() {
        assertThatThrownBy(() -> servicio.me("nadie")).isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    @DisplayName("El productor edita su propia ficha y aparece en su detalle")
    void fichaPropia() {
        servicio.actualizarPerfil(idProductor, new PerfilRequest("12345678-k", "Agricola Sur", "+56911112222", "Talca", 3L), productor);

        var yo = servicio.me("prod-oid");
        assertThat(yo.perfil().rut()).isEqualTo("12345678-K");
        assertThat(yo.perfil().bodegaHabitualId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("Un productor no edita la ficha de otro")
    void fichaAjena() {
        var otro = new IdentidadToken("otro-oid", "o@agrotrack.cl", "Otro", List.of("CLIENTE"));
        servicio.sincronizar(otro);

        assertThatThrownBy(() -> servicio.actualizarPerfil(idProductor,
                new PerfilRequest("12345678-9", "X", null, null, null), otro))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("El admin edita cualquier ficha; un RUT ya usado por otro se rechaza")
    void rutDuplicado() {
        servicio.actualizarPerfil(idProductor, new PerfilRequest("12345678-9", "Agricola Sur", null, null, null), admin);

        assertThatThrownBy(() -> servicio.actualizarPerfil(idAdmin,
                new PerfilRequest("12345678-9", "Otra", null, null, null), admin))
                .isInstanceOf(RutDuplicadoException.class);
    }

    @Test
    @DisplayName("Guardar dos veces la misma ficha no choca con su propio RUT")
    void mismaFichaDosVeces() {
        servicio.actualizarPerfil(idProductor, new PerfilRequest("12345678-9", "Agricola Sur", null, null, null), productor);
        var r = servicio.actualizarPerfil(idProductor, new PerfilRequest("12345678-9", "Agricola Sur SpA", null, null, null), productor);
        assertThat(r.razonSocial()).isEqualTo("Agricola Sur SpA");
    }
}
```

- [ ] **Step 2: Correr y ver que falla**

Run: `./mvnw -q test -Dtest=AdministracionIT`
Expected: FAIL de compilación (`cambiarEstado`, `RutDuplicadoException` no existen).

- [ ] **Step 3: Implementar**

`RutDuplicadoException.java`:
```java
package cl.agrotrack.users.aplicacion;

/** Se traduce a 409. */
public class RutDuplicadoException extends RuntimeException {

    public RutDuplicadoException(String rut) {
        super("El RUT " + rut + " ya pertenece a otra ficha");
    }
}
```

Añadir a `UsuarioService` los imports `cl.agrotrack.users.aplicacion.Dtos.CambioEstadoRequest`, `cl.agrotrack.users.aplicacion.Dtos.PerfilRequest`, `cl.agrotrack.users.aplicacion.Dtos.PerfilResponse`, `cl.agrotrack.users.infraestructura.persistencia.PerfilProductor`, `org.springframework.security.access.AccessDeniedException`, `java.util.List`, `java.util.Map`, `java.util.function.Function`, `java.util.stream.Collectors`, y los métodos:

```java
    @Transactional(readOnly = true)
    public List<UsuarioResponse> listar(EstadoUsuario estado) {
        List<Usuario> lista = estado == null
                ? usuarios.findAllByOrderByPrimerIngresoDesc()
                : usuarios.findByEstadoOrderByPrimerIngresoDesc(estado);
        // Una sola consulta para todas las fichas, no una por fila
        Map<Long, PerfilProductor> fichas = perfiles.findAllById(lista.stream().map(Usuario::getId).toList())
                .stream().collect(Collectors.toMap(PerfilProductor::getUsuarioId, Function.identity()));
        return lista.stream().map(u -> UsuarioResponse.de(u, fichas.get(u.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public UsuarioResponse obtener(Long id) {
        Usuario u = usuario(id);
        return UsuarioResponse.de(u, perfiles.findById(id).orElse(null));
    }

    @Transactional(readOnly = true)
    public UsuarioResponse me(String oid) {
        Usuario u = usuarios.findByAzureOid(oid).orElseThrow(() -> new RecursoNoEncontradoException("Usuario", oid));
        return UsuarioResponse.de(u, perfiles.findById(u.getId()).orElse(null));
    }

    public UsuarioResponse cambiarEstado(Long id, CambioEstadoRequest req, String adminOid) {
        Usuario u = usuario(id);
        u.cambiarEstado(req.estado(), req.motivo(), adminOid, clock.instant());
        return UsuarioResponse.de(u, perfiles.findById(id).orElse(null));
    }

    /** El admin edita cualquier ficha; el productor, solo la suya. */
    public PerfilResponse actualizarPerfil(Long id, PerfilRequest req, IdentidadToken quien) {
        Usuario u = usuario(id);
        if (!quien.esAdmin() && !u.getAzureOid().equals(quien.oid())) {
            throw new AccessDeniedException("Solo puedes editar tu propia ficha");
        }
        String rut = req.rut().trim().toUpperCase();
        perfiles.findByRut(rut)
                .filter(otra -> !otra.getUsuarioId().equals(id))
                .ifPresent(otra -> {
                    throw new RutDuplicadoException(rut);
                });
        PerfilProductor p = perfiles.findById(id).orElseGet(() -> new PerfilProductor(id));
        p.actualizar(rut, req.razonSocial(), req.telefono(), req.direccion(), req.bodegaHabitualId());
        return PerfilResponse.de(perfiles.save(p));
    }

    private Usuario usuario(Long id) {
        return usuarios.findById(id).orElseThrow(() -> new RecursoNoEncontradoException("Usuario", id));
    }
```

- [ ] **Step 4: Correr toda la suite del servicio**

Run: `./mvnw -q test`
Expected: PASS (Schema 4, Máquina 18, Sincronizar 5, Administración 9).

- [ ] **Step 5: Commit**

```bash
git add ms-agrotrack-users/src
git commit -m "feat(users): aprobar, rechazar, desactivar y ficha del productor"
```

---

### Task 5: API HTTP de `users` y errores

**Files:**
- Create: `ms-agrotrack-users/src/main/java/cl/agrotrack/users/infraestructura/web/UsuarioController.java`
- Create: `.../users/infraestructura/web/ApiExceptionHandler.java`
- Test: `ms-agrotrack-users/src/test/java/cl/agrotrack/users/infraestructura/web/UsuarioControllerTest.java`

**Interfaces:**
- Consumes: `UsuarioService` (Tasks 3–4), `IdentidadToken.desde(Jwt)`, `CambioEstadoInvalidoException.motivo()`.
- Produces (HTTP, lo consume el BFF y el frontend):
  - `POST /api/users/sincronizar` → 200 `UsuarioResponse` (autenticado)
  - `GET /api/users?estado=PENDIENTE` → 200 `UsuarioResponse[]` (ADMIN)
  - `GET /api/users/me` → 200 `UsuarioResponse` (autenticado)
  - `GET /api/users/{id}` → 200 (ADMIN)
  - `PUT /api/users/{id}/estado` body `{estado, motivo}` → 200 (ADMIN)
  - `PUT /api/users/{id}/perfil` body `{rut, razonSocial, telefono, direccion, bodegaHabitualId}` → 200 `PerfilResponse` (ADMIN o CLIENTE dueño)
  - Errores problem+json: 404; 400 `campos` / `codigo=MOTIVO_OBLIGATORIO`; 409 `codigo=TRANSICION_NO_PERMITIDA|AUTO_DESACTIVACION|RUT_DUPLICADO|CONFLICTO_CONCURRENTE`

- [ ] **Step 1: Escribir los tests**

```java
package cl.agrotrack.users.infraestructura.web;

import cl.agrotrack.users.aplicacion.Dtos.PerfilResponse;
import cl.agrotrack.users.aplicacion.Dtos.UsuarioResponse;
import cl.agrotrack.users.aplicacion.IdentidadToken;
import cl.agrotrack.users.aplicacion.RutDuplicadoException;
import cl.agrotrack.users.aplicacion.UsuarioService;
import cl.agrotrack.users.config.SecurityConfig;
import cl.agrotrack.users.dominio.CambioEstadoInvalidoException;
import cl.agrotrack.users.dominio.EstadoUsuario;
import cl.agrotrack.users.dominio.MotivoRechazoCambio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Autorizacion por rol, validacion y forma de los errores, sin base de datos. */
@WebMvcTest(UsuarioController.class)
@Import({SecurityConfig.class, ApiExceptionHandler.class})
class UsuarioControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean UsuarioService servicio;

    private static JwtRequestPostProcessor con(String oid, String... roles) {
        return jwt().jwt(j -> j.claim("oid", oid).claim("name", "N " + oid)
                        .claim("preferred_username", oid + "@agrotrack.cl").claim("roles", List.of(roles)))
                .authorities(Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
    }

    private static UsuarioResponse usuario(long id, EstadoUsuario estado) {
        return new UsuarioResponse(id, "oid-" + id, "u@agrotrack.cl", "U", estado, "CLIENTE", null,
                Instant.parse("2026-09-15T10:00:00Z"), Instant.parse("2026-09-15T10:00:00Z"), null, null, null);
    }

    @Test
    @DisplayName("Sin token: 401")
    void sinToken() throws Exception {
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("sincronizar usa los claims del token")
    void sincronizar() throws Exception {
        when(servicio.sincronizar(any())).thenReturn(usuario(1, EstadoUsuario.PENDIENTE));

        mvc.perform(post("/api/users/sincronizar").with(con("oid-1", "CLIENTE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("PENDIENTE"));

        ArgumentCaptor<IdentidadToken> id = ArgumentCaptor.forClass(IdentidadToken.class);
        verify(servicio).sincronizar(id.capture());
        assertThat(id.getValue().oid()).isEqualTo("oid-1");
        assertThat(id.getValue().email()).isEqualTo("oid-1@agrotrack.cl");
        assertThat(id.getValue().roles()).containsExactly("CLIENTE");
    }

    @Test
    @DisplayName("Un productor no lista usuarios: 403")
    void clienteNoLista() throws Exception {
        mvc.perform(get("/api/users").with(con("c", "CLIENTE"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("El admin lista filtrando por estado")
    void adminLista() throws Exception {
        when(servicio.listar(EstadoUsuario.PENDIENTE)).thenReturn(List.of(usuario(2, EstadoUsuario.PENDIENTE)));

        mvc.perform(get("/api/users").param("estado", "PENDIENTE").with(con("a", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(2));
    }

    @Test
    @DisplayName("Estado desconocido en el filtro: 400, no 500")
    void filtroInvalido() throws Exception {
        mvc.perform(get("/api/users").param("estado", "BORRADO").with(con("a", "ADMIN")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("El jefe de acopio no aprueba cuentas: 403")
    void operadorNoAprueba() throws Exception {
        mvc.perform(put("/api/users/2/estado").with(con("o", "OPERADOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"estado\":\"ACTIVO\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Cambiar estado pasa el oid del admin que lo hace")
    void adminAprueba() throws Exception {
        when(servicio.cambiarEstado(eq(2L), any(), eq("admin-oid"))).thenReturn(usuario(2, EstadoUsuario.ACTIVO));

        mvc.perform(put("/api/users/2/estado").with(con("admin-oid", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"estado\":\"ACTIVO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ACTIVO"));
    }

    @Test
    @DisplayName("Auto-desactivacion: 409 con codigo")
    void autoDesactivacion() throws Exception {
        when(servicio.cambiarEstado(eq(1L), any(), any())).thenThrow(
                new CambioEstadoInvalidoException(MotivoRechazoCambio.AUTO_DESACTIVACION, "No puedes desactivar tu propia cuenta"));

        mvc.perform(put("/api/users/1/estado").with(con("a", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"estado\":\"INACTIVO\",\"motivo\":\"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("AUTO_DESACTIVACION"))
                .andExpect(jsonPath("$.detail").value("No puedes desactivar tu propia cuenta"));
    }

    @Test
    @DisplayName("Rechazar sin motivo: 400 con codigo")
    void motivoObligatorio() throws Exception {
        when(servicio.cambiarEstado(eq(2L), any(), any())).thenThrow(
                new CambioEstadoInvalidoException(MotivoRechazoCambio.MOTIVO_OBLIGATORIO, "Indica el motivo"));

        mvc.perform(put("/api/users/2/estado").with(con("a", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"estado\":\"RECHAZADO\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("MOTIVO_OBLIGATORIO"));
    }

    @Test
    @DisplayName("Ficha con RUT mal escrito: 400 con el campo")
    void rutInvalido() throws Exception {
        mvc.perform(put("/api/users/2/perfil").with(con("c", "CLIENTE"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rut\":\"12.345.678-9\",\"razonSocial\":\"A\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.rut").exists());
    }

    @Test
    @DisplayName("Auditor no edita fichas: 403 antes de llegar al servicio")
    void auditorNoEditaFicha() throws Exception {
        mvc.perform(put("/api/users/2/perfil").with(con("x", "AUDITOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rut\":\"12345678-9\",\"razonSocial\":\"A\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("RUT duplicado: 409 con codigo")
    void rutDuplicado() throws Exception {
        when(servicio.actualizarPerfil(eq(2L), any(), any())).thenThrow(new RutDuplicadoException("12345678-9"));

        mvc.perform(put("/api/users/2/perfil").with(con("a", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rut\":\"12345678-9\",\"razonSocial\":\"A\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("RUT_DUPLICADO"));
    }

    @Test
    @DisplayName("El productor guarda su ficha")
    void productorGuarda() throws Exception {
        when(servicio.actualizarPerfil(eq(2L), any(), any()))
                .thenReturn(new PerfilResponse("12345678-9", "Agricola Sur", null, null, null));

        mvc.perform(put("/api/users/2/perfil").with(con("c", "CLIENTE"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rut\":\"12345678-9\",\"razonSocial\":\"Agricola Sur\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.razonSocial").value("Agricola Sur"));
    }

    @Test
    @DisplayName("GET /api/users/me no se confunde con /api/users/{id}")
    void meNoEsId() throws Exception {
        when(servicio.me("c")).thenReturn(usuario(7, EstadoUsuario.ACTIVO));

        mvc.perform(get("/api/users/me").with(con("c", "CLIENTE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7));
        verify(servicio).me("c");
    }

    @Test
    @DisplayName("Listar sin filtro pasa null")
    void listarSinFiltro() throws Exception {
        when(servicio.listar(isNull())).thenReturn(List.of());
        mvc.perform(get("/api/users").with(con("a", "ADMIN"))).andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: Correr y ver que falla**

Run: `./mvnw -q test -Dtest=UsuarioControllerTest`
Expected: FAIL de compilación (`UsuarioController` no existe).

- [ ] **Step 3: Implementar**

`UsuarioController.java`:
```java
package cl.agrotrack.users.infraestructura.web;

import cl.agrotrack.users.aplicacion.Dtos.CambioEstadoRequest;
import cl.agrotrack.users.aplicacion.Dtos.PerfilRequest;
import cl.agrotrack.users.aplicacion.Dtos.PerfilResponse;
import cl.agrotrack.users.aplicacion.Dtos.UsuarioResponse;
import cl.agrotrack.users.aplicacion.IdentidadToken;
import cl.agrotrack.users.aplicacion.UsuarioService;
import cl.agrotrack.users.dominio.EstadoUsuario;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Cuentas de AgroTrack. El rol sigue saliendo del token; aqui solo se
 * decide si la cuenta esta aprobada.
 */
@RestController
@RequestMapping("/api/users")
public class UsuarioController {

    private final UsuarioService servicio;

    public UsuarioController(UsuarioService servicio) {
        this.servicio = servicio;
    }

    /** Lo invoca el BFF desde /api/me en cada ingreso. */
    @PostMapping("/sincronizar")
    @PreAuthorize("isAuthenticated()")
    public UsuarioResponse sincronizar(JwtAuthenticationToken auth) {
        return servicio.sincronizar(IdentidadToken.desde(auth.getToken()));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<UsuarioResponse> listar(@RequestParam(required = false) EstadoUsuario estado) {
        return servicio.listar(estado);
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public UsuarioResponse me(JwtAuthenticationToken auth) {
        return servicio.me(IdentidadToken.desde(auth.getToken()).oid());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public UsuarioResponse obtener(@PathVariable Long id) {
        return servicio.obtener(id);
    }

    @PutMapping("/{id}/estado")
    @PreAuthorize("hasRole('ADMIN')")
    public UsuarioResponse cambiarEstado(@PathVariable Long id, @Valid @RequestBody CambioEstadoRequest req,
                                         JwtAuthenticationToken auth) {
        return servicio.cambiarEstado(id, req, IdentidadToken.desde(auth.getToken()).oid());
    }

    /** El servicio comprueba que un CLIENTE solo toque su propia ficha. */
    @PutMapping("/{id}/perfil")
    @PreAuthorize("hasAnyRole('ADMIN','CLIENTE')")
    public PerfilResponse actualizarPerfil(@PathVariable Long id, @Valid @RequestBody PerfilRequest req,
                                           JwtAuthenticationToken auth) {
        return servicio.actualizarPerfil(id, req, IdentidadToken.desde(auth.getToken()));
    }
}
```

`ApiExceptionHandler.java`:
```java
package cl.agrotrack.users.infraestructura.web;

import cl.agrotrack.users.aplicacion.RecursoNoEncontradoException;
import cl.agrotrack.users.aplicacion.RutDuplicadoException;
import cl.agrotrack.users.dominio.CambioEstadoInvalidoException;
import cl.agrotrack.users.dominio.MotivoRechazoCambio;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/** Errores como RFC 7807, iguales en todos los servicios, con "codigo" cuando hay regla de negocio. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(RecursoNoEncontradoException.class)
    ProblemDetail noEncontrado(RecursoNoEncontradoException e) {
        return problema(HttpStatus.NOT_FOUND, e.getMessage(), null);
    }

    @ExceptionHandler(CambioEstadoInvalidoException.class)
    ProblemDetail cambioInvalido(CambioEstadoInvalidoException e) {
        // Falta un dato: 400. La regla lo impide: 409.
        HttpStatus status = e.motivo() == MotivoRechazoCambio.MOTIVO_OBLIGATORIO ? HttpStatus.BAD_REQUEST : HttpStatus.CONFLICT;
        return problema(status, e.getMessage(), e.motivo().name());
    }

    @ExceptionHandler(RutDuplicadoException.class)
    ProblemDetail rutDuplicado(RutDuplicadoException e) {
        return problema(HttpStatus.CONFLICT, e.getMessage(), "RUT_DUPLICADO");
    }

    /** Dos admins tocando la misma cuenta a la vez, o dos fichas guardadas con el mismo RUT en paralelo. */
    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, DataIntegrityViolationException.class})
    ProblemDetail conflictoConcurrente(RuntimeException e) {
        return problema(HttpStatus.CONFLICT, "Otro cambio se guardo antes; recarga e intenta de nuevo", "CONFLICTO_CONCURRENTE");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail parametroInvalido(MethodArgumentTypeMismatchException e) {
        return problema(HttpStatus.BAD_REQUEST, "Valor invalido para " + e.getName() + ": " + e.getValue(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validacion(MethodArgumentNotValidException e) {
        Map<String, String> campos = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(fe -> campos.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        ProblemDetail p = problema(HttpStatus.BAD_REQUEST, "Datos invalidos", null);
        p.setProperty("campos", campos);
        return p;
    }

    private static ProblemDetail problema(HttpStatus status, String detalle, String codigo) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detalle);
        p.setTitle(status.getReasonPhrase());
        if (codigo != null) {
            p.setProperty("codigo", codigo);
        }
        return p;
    }
}
```

- [ ] **Step 4: Correr toda la suite**

Run: `./mvnw -q test`
Expected: PASS (todas las clases del servicio). Un `AccessDeniedException` lanzado desde el servicio no lo captura este handler: sube hasta Spring Security y sale como 403, que es lo que se quiere.

- [ ] **Step 5: Empaquetar para los scripts locales**

Run: `./mvnw -q -DskipTests package && ls target/*.jar`
Expected: `target/ms-agrotrack-users-0.0.1-SNAPSHOT.jar`.

- [ ] **Step 6: Commit**

```bash
git add ms-agrotrack-users/src
git commit -m "feat(users): endpoints de administracion con errores problem+json"
```

---

### Task 6: BFF — cliente de `users` y caché de 60 s

**Files:**
- Create: `ms-agrotrack-bff/src/main/java/cl/agrotrack/bff/cuenta/CuentaUsuario.java`
- Create: `.../bff/cuenta/UsuariosNoDisponibleException.java`, `.../bff/cuenta/ClienteUsuarios.java`, `.../bff/cuenta/ClienteUsuariosHttp.java`, `.../bff/cuenta/EstadoCuentas.java`
- Create: `ms-agrotrack-bff/src/main/java/cl/agrotrack/bff/config/RelojConfig.java`
- Modify: `ms-agrotrack-bff/src/main/resources/application.yml` (servicio `users`)
- Test: `ms-agrotrack-bff/src/test/java/cl/agrotrack/bff/cuenta/EstadoCuentasTest.java`

**Interfaces:**
- Consumes: `POST {users}/api/users/sincronizar` (Task 5), que devuelve JSON con al menos `id`, `estado`, `motivo`; `ProxyController.Servicios.urlDe(String)` (existente).
- Produces:
  - `record CuentaUsuario(Long id, String estado, String motivo, Instant primerIngreso)` con `boolean activa()`
  - `interface ClienteUsuarios { CuentaUsuario sincronizar(String bearer); }` — lanza `UsuariosNoDisponibleException`
  - `class EstadoCuentas { CuentaUsuario consultar(String oid, String bearer); CuentaUsuario sincronizar(String oid, String bearer); }`
  - propiedad `agrotrack.servicios.users` (`USERS_URL`, por defecto `http://localhost:8089`)

- [ ] **Step 1: Escribir el test de la caché**

```java
package cl.agrotrack.bff.cuenta;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** La cache evita una llamada a users por request, sin servir un estado viejo mas de 60 s. */
class EstadoCuentasTest {

    static class RelojMovible extends Clock {
        Instant ahora = Instant.parse("2026-09-15T12:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return ahora; }

        void avanzar(Duration d) { ahora = ahora.plus(d); }
    }

    /** users de mentira: devuelve el estado que se le diga y cuenta las llamadas. */
    static class UsersFalso implements ClienteUsuarios {
        final AtomicInteger llamadas = new AtomicInteger();
        String estado = "PENDIENTE";
        boolean caido = false;

        @Override
        public CuentaUsuario sincronizar(String bearer) {
            llamadas.incrementAndGet();
            if (caido) throw new UsuariosNoDisponibleException("caido", null);
            return new CuentaUsuario(1L, estado, null, null);
        }
    }

    RelojMovible reloj;
    UsersFalso users;
    EstadoCuentas cuentas;

    @BeforeEach
    void preparar() {
        reloj = new RelojMovible();
        users = new UsersFalso();
        cuentas = new EstadoCuentas(users, reloj);
    }

    @Test
    @DisplayName("Dentro de 60 s se responde desde la cache: una sola llamada")
    void cacheVigente() {
        cuentas.consultar("oid", "t");
        reloj.avanzar(Duration.ofSeconds(59));
        cuentas.consultar("oid", "t");

        assertThat(users.llamadas).hasValue(1);
    }

    @Test
    @DisplayName("Pasados 60 s se vuelve a preguntar y se ve el estado nuevo")
    void cacheVencida() {
        cuentas.consultar("oid", "t");
        users.estado = "ACTIVO";
        reloj.avanzar(Duration.ofSeconds(61));

        assertThat(cuentas.consultar("oid", "t").estado()).isEqualTo("ACTIVO");
        assertThat(users.llamadas).hasValue(2);
    }

    @Test
    @DisplayName("sincronizar siempre llama y refresca la cache (lo usa /api/me)")
    void sincronizarRefresca() {
        cuentas.consultar("oid", "t");
        users.estado = "ACTIVO";

        cuentas.sincronizar("oid", "t");

        assertThat(cuentas.consultar("oid", "t").estado()).isEqualTo("ACTIVO");
        assertThat(users.llamadas).hasValue(2);
    }

    @Test
    @DisplayName("Si users cae con la cache vencida, se propaga el fallo: nunca se sirve el estado viejo")
    void caidoNoSirveViejo() {
        users.estado = "ACTIVO";
        cuentas.consultar("oid", "t");
        reloj.avanzar(Duration.ofSeconds(61));
        users.caido = true;

        assertThatThrownBy(() -> cuentas.consultar("oid", "t")).isInstanceOf(UsuariosNoDisponibleException.class);
    }

    @Test
    @DisplayName("Cada usuario tiene su propia entrada")
    void porUsuario() {
        cuentas.consultar("a", "t");
        cuentas.consultar("b", "t");

        assertThat(users.llamadas).hasValue(2);
    }
}
```

- [ ] **Step 2: Correr y ver que falla**

Run: `cd ms-agrotrack-bff && ./mvnw -q test -Dtest=EstadoCuentasTest`
Expected: FAIL de compilación (`EstadoCuentas` no existe).

- [ ] **Step 3: Implementar**

`CuentaUsuario.java`:
```java
package cl.agrotrack.bff.cuenta;

import java.time.Instant;

/** Lo que el BFF necesita saber de la cuenta. users devuelve mas campos; se ignoran. */
public record CuentaUsuario(Long id, String estado, String motivo, Instant primerIngreso) {

    public boolean activa() {
        return "ACTIVO".equals(estado);
    }
}
```

`UsuariosNoDisponibleException.java`:
```java
package cl.agrotrack.bff.cuenta;

/** users no respondio o respondio mal. El BFF lo traduce a 503: ante la duda, no se deja pasar. */
public class UsuariosNoDisponibleException extends RuntimeException {

    public UsuariosNoDisponibleException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
```

`ClienteUsuarios.java`:
```java
package cl.agrotrack.bff.cuenta;

/** Interfaz para probar la cache y el filtro sin red. */
public interface ClienteUsuarios {

    /** Registra o actualiza al dueno del token en ms-agrotrack-users y devuelve su cuenta. */
    CuentaUsuario sincronizar(String bearer);
}
```

`ClienteUsuariosHttp.java`:
```java
package cl.agrotrack.bff.cuenta;

import cl.agrotrack.bff.infraestructura.web.ProxyController;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

@Component
public class ClienteUsuariosHttp implements ClienteUsuarios {

    private final RestClient rest;
    private final ProxyController.Servicios servicios;

    public ClienteUsuariosHttp(RestClient.Builder builder, ProxyController.Servicios servicios) {
        // Timeouts cortos: esta llamada va delante de cada request de un usuario.
        // Sin ellos, un users colgado dejaria colgado a todo el BFF.
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(Duration.ofSeconds(2));
        fabrica.setReadTimeout(Duration.ofSeconds(5));
        this.rest = builder.requestFactory(fabrica).build();
        this.servicios = servicios;
    }

    @Override
    public CuentaUsuario sincronizar(String bearer) {
        String base = servicios.urlDe("users");
        if (base == null) {
            throw new UsuariosNoDisponibleException("agrotrack.servicios.users no esta configurado", null);
        }
        try {
            CuentaUsuario cuenta = rest.post()
                    .uri(base + "/api/users/sincronizar")
                    .headers(h -> h.setBearerAuth(bearer))
                    .retrieve()
                    .body(CuentaUsuario.class);
            if (cuenta == null || cuenta.estado() == null) {
                throw new UsuariosNoDisponibleException("ms-agrotrack-users respondio sin estado", null);
            }
            return cuenta;
        } catch (RestClientException e) {
            throw new UsuariosNoDisponibleException("ms-agrotrack-users no respondio: " + e.getMessage(), e);
        }
    }
}
```

`EstadoCuentas.java`:
```java
package cl.agrotrack.bff.cuenta;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Estado de cada cuenta con 60 s de vigencia. Sin esto habria una llamada
 * extra a users por cada request; con esto, desactivar a alguien surte
 * efecto en menos de un minuto.
 */
@Component
public class EstadoCuentas {

    static final Duration VIGENCIA = Duration.ofSeconds(60);

    private record Entrada(CuentaUsuario cuenta, Instant vence) {
    }

    private final Map<String, Entrada> cache = new ConcurrentHashMap<>();
    private final ClienteUsuarios cliente;
    private final Clock clock;

    public EstadoCuentas(ClienteUsuarios cliente, Clock clock) {
        this.cliente = cliente;
        this.clock = clock;
    }

    /** Para el filtro: responde desde la cache si esta vigente. */
    public CuentaUsuario consultar(String oid, String bearer) {
        Entrada e = cache.get(oid);
        if (e != null && clock.instant().isBefore(e.vence())) {
            return e.cuenta();
        }
        return sincronizar(oid, bearer);
    }

    /** Para /api/me: siempre pregunta. Si users falla, la excepcion sube y la entrada vieja no se usa. */
    public CuentaUsuario sincronizar(String oid, String bearer) {
        CuentaUsuario cuenta = cliente.sincronizar(bearer);
        cache.put(oid, new Entrada(cuenta, clock.instant().plus(VIGENCIA)));
        return cuenta;
    }
}
```

`config/RelojConfig.java`:
```java
package cl.agrotrack.bff.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class RelojConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
```

En `application.yml`, dentro de `agrotrack.servicios`, añadir tras `kafka`:
```yaml
    users: ${USERS_URL:http://localhost:8089}
```

- [ ] **Step 4: Correr y ver que pasa**

Run: `./mvnw -q test -Dtest=EstadoCuentasTest`
Expected: 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add ms-agrotrack-bff/src
git commit -m "feat(bff): cliente de users con cache de estado de 60 s"
```

---

### Task 7: BFF — `/api/me` registra, el filtro bloquea y la matriz cubre `/api/users`

**Files:**
- Create: `ms-agrotrack-bff/src/main/java/cl/agrotrack/bff/cuenta/FiltroCuentaActiva.java`
- Modify: `ms-agrotrack-bff/src/main/java/cl/agrotrack/bff/config/SecurityConfig.java`
- Modify: `ms-agrotrack-bff/src/main/java/cl/agrotrack/bff/infraestructura/web/MeController.java`
- Modify: `ms-agrotrack-bff/src/test/java/cl/agrotrack/bff/infraestructura/web/BffSeguridadTest.java`
- Test: `ms-agrotrack-bff/src/test/java/cl/agrotrack/bff/cuenta/BffCuentaTest.java`

**Interfaces:**
- Consumes: `EstadoCuentas.consultar/sincronizar`, `CuentaUsuario`, `UsuariosNoDisponibleException` (Task 6).
- Produces (HTTP, lo consume el frontend):
  - `GET /api/me` → `{ userId, nombre, email, roles[], usuarioId, estado, motivo, primerIngreso }`
  - 403 problem+json `{ codigo: CUENTA_PENDIENTE|CUENTA_RECHAZADA|CUENTA_INACTIVA, estado, detail }` en cualquier `/api/**` salvo `/api/me`
  - 503 problem+json `{ codigo: USUARIOS_NO_DISPONIBLE }`
  - Matriz: `GET /api/users/me` autenticado · `PUT /api/users/*/perfil` ADMIN, CLIENTE · `GET /api/users`, `GET /api/users/*` ADMIN · `PUT /api/users/*/estado` ADMIN · `POST /api/users/sincronizar` **denegado** desde fuera

- [ ] **Step 1: Escribir los tests nuevos**

```java
package cl.agrotrack.bff.cuenta;

import cl.agrotrack.bff.config.JwtRolesConverter;
import cl.agrotrack.bff.config.SecurityConfig;
import cl.agrotrack.bff.infraestructura.web.MeController;
import cl.agrotrack.bff.infraestructura.web.ProxyController;
import cl.agrotrack.bff.infraestructura.web.Reenviador;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Segunda capa de autorizacion: el token trae el rol Y la cuenta esta
 * aprobada. La matriz de roles va primero; este filtro, despues.
 */
@WebMvcTest({ProxyController.class, MeController.class, ProxyController.Servicios.class})
@Import(SecurityConfig.class)
class BffCuentaTest {

    @Autowired MockMvc mvc;
    @MockitoBean Reenviador reenviador;
    @MockitoBean EstadoCuentas cuentas;

    @BeforeEach
    void preparar() {
        when(reenviador.reenviar(any(), any(), any(), any()))
                .thenReturn(ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("{\"ok\":true}".getBytes()));
    }

    private static JwtRequestPostProcessor con(String... roles) {
        Jwt base = Jwt.withTokenValue("t").header("alg", "none").claim("roles", List.of(roles)).claim("oid", "u-1").build();
        return jwt().jwt(j -> j.claim("roles", List.of(roles)).claim("oid", "u-1").claim("name", "Diego")
                        .claim("preferred_username", "diego@agrotrack.local"))
                .authorities(new JwtRolesConverter().convert(base).getAuthorities());
    }

    private void cuentaEn(String estado) {
        CuentaUsuario c = new CuentaUsuario(9L, estado, null, Instant.parse("2026-09-15T10:00:00Z"));
        when(cuentas.consultar(eq("u-1"), any())).thenReturn(c);
        when(cuentas.sincronizar(eq("u-1"), any())).thenReturn(c);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"PENDIENTE,CUENTA_PENDIENTE", "RECHAZADO,CUENTA_RECHAZADA", "INACTIVO,CUENTA_INACTIVA"})
    @DisplayName("Cuenta no activa: 403 con su codigo y nada se reenvia")
    void noActivaBloqueada(String estado, String codigo) throws Exception {
        cuentaEn(estado);

        mvc.perform(get("/api/deliveries").with(con("CLIENTE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value(codigo))
                .andExpect(jsonPath("$.estado").value(estado));
        verify(reenviador, never()).reenviar(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Un PENDIENTE si puede pedir /api/me, que ademas lo registra")
    void pendienteVeSuEstado() throws Exception {
        cuentaEn("PENDIENTE");

        mvc.perform(get("/api/me").with(con("CLIENTE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.usuarioId").value(9))
                .andExpect(jsonPath("$.primerIngreso").value("2026-09-15T10:00:00Z"));
        verify(cuentas).sincronizar(eq("u-1"), any());
        verify(cuentas, never()).consultar(any(), any());
    }

    @Test
    @DisplayName("Cuenta ACTIVA: pasa y se reenvia")
    void activaPasa() throws Exception {
        cuentaEn("ACTIVO");

        mvc.perform(get("/api/deliveries").with(con("CLIENTE"))).andExpect(status().isOk());
        verify(reenviador).reenviar(eq(HttpMethod.GET), any(), any(), any());
    }

    @Test
    @DisplayName("users caido: 503 en el filtro, nunca se deja pasar")
    void usersCaidoFiltro() throws Exception {
        when(cuentas.consultar(any(), any())).thenThrow(new UsuariosNoDisponibleException("caido", null));

        mvc.perform(get("/api/deliveries").with(con("ADMIN")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.codigo").value("USUARIOS_NO_DISPONIBLE"));
        verify(reenviador, never()).reenviar(any(), any(), any(), any());
    }

    @Test
    @DisplayName("users caido: 503 tambien en /api/me")
    void usersCaidoMe() throws Exception {
        when(cuentas.sincronizar(any(), any())).thenThrow(new UsuariosNoDisponibleException("caido", null));

        mvc.perform(get("/api/me").with(con("ADMIN")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.codigo").value("USUARIOS_NO_DISPONIBLE"));
    }

    @Test
    @DisplayName("La matriz va primero: un CLIENTE en /api/users recibe 403 sin consultar su cuenta")
    void matrizAntesQueCuenta() throws Exception {
        mvc.perform(get("/api/users").with(con("CLIENTE"))).andExpect(status().isForbidden());
        verify(cuentas, never()).consultar(any(), any());
    }

    @Test
    @DisplayName("ADMIN activo lista usuarios: se reenvia a users con la query")
    void adminListaUsuarios() throws Exception {
        cuentaEn("ACTIVO");

        mvc.perform(get("/api/users").param("estado", "PENDIENTE").with(con("ADMIN"))).andExpect(status().isOk());

        ArgumentCaptor<URI> destino = ArgumentCaptor.forClass(URI.class);
        verify(reenviador).reenviar(eq(HttpMethod.GET), destino.capture(), any(), any());
        assertThat(destino.getValue().toString()).isEqualTo("http://localhost:8089/api/users?estado=PENDIENTE");
    }

    @Test
    @DisplayName("El productor activo guarda su ficha; el auditor no")
    void fichaPorRol() throws Exception {
        cuentaEn("ACTIVO");
        String ficha = "{\"rut\":\"12345678-9\",\"razonSocial\":\"A\"}";

        mvc.perform(put("/api/users/9/perfil").with(con("CLIENTE")).contentType(MediaType.APPLICATION_JSON).content(ficha))
                .andExpect(status().isOk());
        mvc.perform(put("/api/users/9/perfil").with(con("AUDITOR")).contentType(MediaType.APPLICATION_JSON).content(ficha))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Solo el ADMIN cambia estados de cuentas")
    void estadoSoloAdmin() throws Exception {
        cuentaEn("ACTIVO");
        String body = "{\"estado\":\"ACTIVO\"}";

        mvc.perform(put("/api/users/5/estado").with(con("OPERADOR")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/users/5/estado").with(con("ADMIN")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("sincronizar no se expone hacia fuera, ni para ADMIN")
    void sincronizarNoExpuesto() throws Exception {
        mvc.perform(post("/api/users/sincronizar").with(con("ADMIN"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Cualquier rol activo consulta su propia cuenta")
    void usersMe() throws Exception {
        cuentaEn("ACTIVO");
        mvc.perform(get("/api/users/me").with(con("AUDITOR"))).andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: Correr y ver que falla**

Run: `./mvnw -q test -Dtest=BffCuentaTest`
Expected: FAIL — p. ej. `noActivaBloqueada` recibe 200 (no hay filtro) y `pendienteVeSuEstado` no encuentra `$.estado`.

- [ ] **Step 3: El filtro**

`FiltroCuentaActiva.java`:
```java
package cl.agrotrack.bff.cuenta;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Segunda capa: tras la matriz de roles, comprueba que la cuenta este
 * ACTIVA en ms-agrotrack-users. Asi un despedido deja de entrar aunque su
 * cuenta de Microsoft siga viva.
 *
 * <p>No es un @Component a proposito: Spring Boot registraria cualquier
 * Filter bean en el contenedor, delante de Spring Security, donde todavia
 * no hay usuario autenticado. Se instancia en SecurityConfig.
 */
public class FiltroCuentaActiva extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FiltroCuentaActiva.class);

    private record Bloqueo(String codigo, String mensaje) {
    }

    private static final Map<String, Bloqueo> BLOQUEOS = Map.of(
            "PENDIENTE", new Bloqueo("CUENTA_PENDIENTE", "Tu cuenta espera aprobación"),
            "RECHAZADO", new Bloqueo("CUENTA_RECHAZADA", "Tu solicitud fue rechazada"),
            "INACTIVO", new Bloqueo("CUENTA_INACTIVA", "Tu cuenta fue desactivada"));

    private final EstadoCuentas cuentas;
    private final ObjectMapper json;

    public FiltroCuentaActiva(EstadoCuentas cuentas, ObjectMapper json) {
        this.cuentas = cuentas;
        this.json = json;
    }

    /** /api/me queda fuera: es por donde se registra y por donde /pendiente sabe que mostrar. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String ruta = request.getRequestURI();
        return !ruta.startsWith("/api/") || ruta.equals("/api/me") || HttpMethod.OPTIONS.matches(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken token)) {
            chain.doFilter(request, response);
            return;
        }
        String oid = token.getToken().getClaimAsString("oid");
        if (oid == null) {
            oid = token.getToken().getSubject();
        }

        CuentaUsuario cuenta;
        try {
            cuenta = cuentas.consultar(oid, token.getToken().getTokenValue());
        } catch (UsuariosNoDisponibleException e) {
            log.warn("No se pudo verificar la cuenta {}: {}", oid, e.getMessage());
            escribir(response, HttpStatus.SERVICE_UNAVAILABLE, "USUARIOS_NO_DISPONIBLE",
                    "No se pudo verificar tu cuenta; intenta en un momento", null);
            return;
        }

        if (cuenta.activa()) {
            chain.doFilter(request, response);
            return;
        }
        Bloqueo b = BLOQUEOS.getOrDefault(cuenta.estado(), new Bloqueo("CUENTA_NO_ACTIVA", "Tu cuenta no está activa"));
        escribir(response, HttpStatus.FORBIDDEN, b.codigo(), b.mensaje(), cuenta.estado());
    }

    private void escribir(HttpServletResponse response, HttpStatus status, String codigo, String detalle, String estado)
            throws IOException {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detalle);
        p.setTitle(status.getReasonPhrase());
        p.setProperty("codigo", codigo);
        if (estado != null) {
            p.setProperty("estado", estado);
        }
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), p);
    }
}
```

- [ ] **Step 4: `SecurityConfig`** — la firma del bean recibe `EstadoCuentas` y `ObjectMapper`; se añaden las reglas de `/api/users` antes de `anyRequest()` y el filtro tras `AuthorizationFilter`.

Imports nuevos: `cl.agrotrack.bff.cuenta.EstadoCuentas`, `cl.agrotrack.bff.cuenta.FiltroCuentaActiva`, `com.fasterxml.jackson.databind.ObjectMapper`, `org.springframework.security.web.access.intercept.AuthorizationFilter`.

```java
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, EstadoCuentas cuentas, ObjectMapper json) throws Exception {
```

Justo antes de `// Lo que no esta en la matriz no pasa...`:
```java
                        // Usuarios: la ficha propia la edita el productor; administrar es del admin.
                        // POST /api/users/sincronizar no figura: lo llama el BFF desde /api/me.
                        .requestMatchers(HttpMethod.GET, "/api/users/me").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/users/*/perfil").hasAnyRole("ADMIN", "CLIENTE")
                        .requestMatchers(HttpMethod.PUT, "/api/users/*/estado").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/users", "/api/users/*").hasRole("ADMIN")

```

Tras `.oauth2ResourceServer(...)` y antes de `.build()`:
```java
                // Despues de la matriz: solo se consulta la cuenta de quien ya tiene el rol.
                .addFilterAfter(new FiltroCuentaActiva(cuentas, json), AuthorizationFilter.class)
```

Actualizar el javadoc de la clase añadiendo: `<p>Segunda capa: FiltroCuentaActiva exige ademas que la cuenta este ACTIVA en ms-agrotrack-users.`

- [ ] **Step 5: `MeController`**

```java
package cl.agrotrack.bff.infraestructura.web;

import cl.agrotrack.bff.cuenta.CuentaUsuario;
import cl.agrotrack.bff.cuenta.EstadoCuentas;
import cl.agrotrack.bff.cuenta.UsuariosNoDisponibleException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Quien soy segun el token, y en que estado esta mi cuenta. Lo primero que
 * pide el frontend tras el login; de paso registra al usuario en users, asi
 * el alta ocurre sola y en un unico lugar.
 */
@RestController
public class MeController {

    public record Yo(String userId, String nombre, String email, List<String> roles,
                     Long usuarioId, String estado, String motivo, Instant primerIngreso) {
    }

    private final EstadoCuentas cuentas;

    public MeController(EstadoCuentas cuentas) {
        this.cuentas = cuentas;
    }

    @GetMapping("/api/me")
    public Yo me(JwtAuthenticationToken auth) {
        var jwt = auth.getToken();
        String oid = jwt.getClaimAsString("oid");
        String userId = oid != null ? oid : jwt.getSubject();
        String email = jwt.getClaimAsString("preferred_username");
        if (email == null) {
            email = jwt.getClaimAsString("email");
        }
        List<String> roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .sorted()
                .toList();
        CuentaUsuario cuenta = cuentas.sincronizar(userId, jwt.getTokenValue());
        return new Yo(userId, jwt.getClaimAsString("name"), email, roles, cuenta.id(), cuenta.estado(), cuenta.motivo(), cuenta.primerIngreso());
    }

    @ExceptionHandler(UsuariosNoDisponibleException.class)
    ProblemDetail usuariosNoDisponible(UsuariosNoDisponibleException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "No se pudo verificar tu cuenta; intenta en un momento");
        p.setTitle("Service Unavailable");
        p.setProperty("codigo", "USUARIOS_NO_DISPONIBLE");
        return p;
    }
}
```

- [ ] **Step 6: Adaptar `BffSeguridadTest`** — ahora el contexto necesita `EstadoCuentas`, y sin cuenta activa todo daría 403.

Añadir imports `cl.agrotrack.bff.cuenta.CuentaUsuario`, `cl.agrotrack.bff.cuenta.EstadoCuentas`; el campo y el stub:
```java
    @MockitoBean EstadoCuentas cuentas;

    @BeforeEach
    void reenviadorResponde() {
        when(reenviador.reenviar(any(), any(), any(), any()))
                .thenReturn(ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("{\"ok\":true}".getBytes()));
        // Estas pruebas son de la matriz de roles: la cuenta esta activa.
        CuentaUsuario activa = new CuentaUsuario(1L, "ACTIVO", null, null);
        when(cuentas.consultar(any(), any())).thenReturn(activa);
        when(cuentas.sincronizar(any(), any())).thenReturn(activa);
    }
```

Y en el test `me()` añadir al final de la cadena:
```java
                .andExpect(jsonPath("$.estado").value("ACTIVO"))
                .andExpect(jsonPath("$.usuarioId").value(1));
```

- [ ] **Step 7: Correr toda la suite del BFF**

Run: `./mvnw -q test`
Expected: PASS (`BffSeguridadTest` completo, `BffCuentaTest` 13, `EstadoCuentasTest` 5).

- [ ] **Step 8: Empaquetar y commit**

```bash
./mvnw -q -DskipTests package
cd .. && git add ms-agrotrack-bff/src
git commit -m "feat(bff): /api/me registra al usuario y solo las cuentas activas pasan"
```

---

### Task 8: Infraestructura, scripts y documentación del backend

**Files:**
- Modify: `infra/apps/compose.yml`
- Modify: `infra/.env.aws.example`, `infra/aws/crear.ps1:146`
- Modify: `infra/local/smoke.ps1`, `infra/local/azure-local.ps1`, `infra/aws/smoke-aws.ps1`
- Modify: `README.md`, `docs/00-decisiones.md`, `docs/06-modelo-de-datos.md`, `docs/10-checklist-demo.md`

**Interfaces:**
- Consumes: jar de `users` (Task 5) y del BFF (Task 7); `02-users.sql` (Task 1); `Yo.estado`/`Yo.usuarioId` (Task 7); `PUT /api/users/{id}/estado` (Task 5).
- Produces: contenedor `at-users` en `apps`; variable `POSTGRES_USERS_PASSWORD`; `USERS_URL` en el BFF; smoke que prueba bloqueo y aprobación.

- [ ] **Step 1: `infra/apps/compose.yml`** — nuevo servicio tras `report`, y `USERS_URL` en el BFF:

```yaml
  # Cuentas y aprobacion (docs/planes/2026-09-08-administracion-usuarios-design.md)
  users:
    <<: *svc
    build: ../../ms-agrotrack-users
    container_name: at-users
    ports: ["8089:8080"]
    depends_on:
      postgres: { condition: service_healthy }
    environment:
      <<: *svc-env
      SPRING_DATASOURCE_URL: jdbc:postgresql://${POSTGRES_HOST}:5432/agro_users
      SPRING_DATASOURCE_USERNAME: agro_users
      SPRING_DATASOURCE_PASSWORD: ${POSTGRES_USERS_PASSWORD}
```

En `bff.environment`, tras `KAFKA_ADMIN_URL`:
```yaml
      USERS_URL: http://users:8080
```

- [ ] **Step 2: Contraseña en los `.env`**

En `infra/.env.aws.example`, tras `POSTGRES_REPORT_PASSWORD=agro_report`:
```
POSTGRES_USERS_PASSWORD=agro_users
```
En `infra/aws/crear.ps1`, tras la línea 146 (`POSTGRES_REPORT_PASSWORD=agro_report`), la misma línea. Y en el `infra/.env.aws` real (no versionado) añadirla a mano:
```bash
cd /c/Users/deint/Desktop/AgroTrack/infra
grep -q POSTGRES_USERS_PASSWORD .env.aws || sed -i '/^POSTGRES_REPORT_PASSWORD=/a POSTGRES_USERS_PASSWORD=agro_users' .env.aws
grep -c POSTGRES_USERS_PASSWORD .env.aws
```
Expected: `1`.

- [ ] **Step 3: `infra/local/azure-local.ps1`**

- `foreach ($puerto in 8081..8088)` → `foreach ($puerto in 8081..8089)`
- En `$servicios`, la línea `'ms-agrotrack-report' = 8085;   'ms-agrotrack-bff' = 8081` pasa a:
```powershell
  'ms-agrotrack-report' = 8085;   'ms-agrotrack-users' = 8089
  'ms-agrotrack-bff' = 8081
```
- En el `.SYNOPSIS`, "los 8 servicios" → "los 9 servicios".

- [ ] **Step 4: `infra/local/smoke.ps1`**

1. `foreach ($puerto in 8081..8088)` → `8081..8089`; "Arranca los 8 servicios" → "los 9 servicios".
2. En `$servicios`, antes de `'ms-agrotrack-bff'         = 8081`:
```powershell
  'ms-agrotrack-users'       = 8089
```
3. Reemplazar el bloque `Paso 'Tokens'` … hasta la línea `Verificar ($yo.roles -contains 'OPERADOR') ...` (incluido `Paso '/api/me'`) por:

```powershell
  Paso 'Tokens'
  $mint = Join-Path $PSScriptRoot 'jwt\mint.mjs'
  # Oids nuevos en cada corrida: operador, productor y auditor nacen PENDIENTE
  # y la aprobacion se prueba de verdad, no contra datos de la corrida anterior.
  $corrida = Get-Date -Format 'yyMMddHHmmss'
  $tAdmin = (& node $mint ADMIN admin-smoke).Trim()
  $tOper  = (& node $mint OPERADOR "operador-smoke-$corrida").Trim()
  $tCli   = (& node $mint CLIENTE "productor-smoke-$corrida").Trim()
  $tAud   = (& node $mint AUDITOR "auditor-smoke-$corrida").Trim()
  Verificar ($tAdmin.Length -gt 100) 'tokens emitidos'

  Paso 'Cuentas: registro al entrar, bloqueo y aprobacion'
  $yoAdmin = Api GET '/api/me' $tAdmin
  if ($yoAdmin.estado -ne 'ACTIVO') {
    # El primer ADMIN que entra queda activo solo. Si la base local ya tenia
    # otro admin activo (pruebas a mano con el frontend), se aprueba a
    # admin-smoke directo en la base. Es el unico atajo del smoke.
    & docker exec at-postgres psql -U agro_users -d agro_users -c "UPDATE usuario SET estado='ACTIVO', aprobado_por='smoke', aprobado_en=now() WHERE azure_oid='admin-smoke'" | Out-Null
    $yoAdmin = Api GET '/api/me' $tAdmin
  }
  Verificar ($yoAdmin.estado -eq 'ACTIVO') 'admin-smoke ACTIVO'

  $nuevos = @()
  foreach ($t in @($tOper, $tCli, $tAud)) { $nuevos += Api GET '/api/me' $t }
  Verificar ((@($nuevos | Where-Object estado -eq 'PENDIENTE')).Count -eq 3) 'operador, productor y auditor nacen PENDIENTE'

  try { Api GET '/api/deliveries' $tCli | Out-Null; Falla 'un PENDIENTE pudo listar entregas' }
  catch { Verificar ($_.Exception.Response.StatusCode.value__ -eq 403) 'PENDIENTE recibe 403 en /api/deliveries' }

  foreach ($n in $nuevos) { Api PUT "/api/users/$($n.usuarioId)/estado" $tAdmin @{ estado = 'ACTIVO' } | Out-Null }
  # /api/me vuelve a sincronizar y refresca la cache del BFF; sin esto el
  # productor seguiria recibiendo 403 hasta 60 s.
  $yo = Api GET '/api/me' $tOper
  foreach ($t in @($tCli, $tAud)) { Api GET '/api/me' $t | Out-Null }
  Verificar ($yo.estado -eq 'ACTIVO' -and $yo.roles -contains 'OPERADOR') "aprobados por el admin; me como OPERADOR ($($yo.userId))"
```

4. En `Paso 'Matriz de roles del BFF'`, tras la línea del OPERADOR en `/api/audit`:
```powershell
  try { Api GET '/api/users' $tOper; Falla 'OPERADOR listo usuarios' } catch { Verificar ($_.Exception.Response.StatusCode.value__ -eq 403) 'OPERADOR 403 en /api/users' }
```

- [ ] **Step 5: `infra/aws/smoke-aws.ps1`** — justo después de `$yo = Api GET '/api/me'`:

```powershell
if ($yo.estado -ne 'ACTIVO') {
  Falla "tu cuenta esta $($yo.estado) en AgroTrack: apruebala en /usuarios con un admin activo"
  exit 1
}
```

- [ ] **Step 6: Prueba de humo local (la verificación de toda la parte backend)**

```bash
cd /c/Users/deint/Desktop/AgroTrack/infra
docker compose --env-file .env -f local/compose.yml up -d
docker exec -i at-postgres psql -U agrotrack -d postgres < local/init-postgres/02-users.sql
export JAVA_HOME="/c/Users/deint/tools/jdk-21.0.12.1+1"
cd .. && for s in ms-agrotrack-*; do (cd $s && ./mvnw -q -DskipTests package); done
powershell -File infra/local/smoke.ps1
```
Expected: `SMOKE OK`, con las líneas nuevas `admin-smoke ACTIVO`, `nacen PENDIENTE`, `PENDIENTE recibe 403`, `aprobados por el admin` y `OPERADOR 403 en /api/users`. Si falla un paso, mirar `infra/local/logs/ms-agrotrack-users.log` y `ms-agrotrack-bff.log`.

- [ ] **Step 7: Documentación**

`README.md`: en la tabla, tras `ms-agrotrack-report`:
```markdown
| `ms-agrotrack-users` | Cuentas: registro al primer ingreso, aprobación por el admin y ficha del productor |
```
y "El smoke test arranca los 8 servicios" → "los 9 servicios".

`docs/06-modelo-de-datos.md`, antes de `## Cómo se crean las tablas`:
```markdown
## Esquema `agro_users` — propiedad de `ms-agrotrack-users`

Quién puede usar AgroTrack. **Los roles no viven aquí**: siguen saliendo del
claim `roles` del token de Azure AD. Esta base decide solo si la cuenta está
aprobada. Detalle en `docs/planes/2026-09-08-administracion-usuarios-design.md`.

### `USUARIO`

| Columna | Tipo | Notas |
|---|---|---|
| `ID` | `BIGINT` | PK, `seq_usuario` |
| `AZURE_OID` | `VARCHAR(50)` | único: el claim `oid` |
| `EMAIL`, `NOMBRE` | `VARCHAR(200)` | del token, se refrescan en cada ingreso |
| `ESTADO` | `VARCHAR(20)` | `PENDIENTE` · `ACTIVO` · `RECHAZADO` · `INACTIVO` (CHECK) |
| `ROL_ULTIMO_TOKEN` | `VARCHAR(20)` | informativo, nunca autoriza |
| `MOTIVO` | `VARCHAR(500)` | por qué se rechazó o desactivó |
| `PRIMER_INGRESO`, `ULTIMO_INGRESO` | `TIMESTAMPTZ` | |
| `APROBADO_POR`, `APROBADO_EN` | `VARCHAR(50)`, `TIMESTAMPTZ` | `oid` del admin, o `sistema` para el primer admin |
| `VERSION` | `BIGINT` | bloqueo optimista |

### `PERFIL_PRODUCTOR`

| Columna | Tipo | Notas |
|---|---|---|
| `USUARIO_ID` | `BIGINT` | PK y FK a `USUARIO` |
| `RUT` | `VARCHAR(15)` | único, `12345678-9` |
| `RAZON_SOCIAL` | `VARCHAR(200)` | |
| `TELEFONO`, `DIRECCION` | `VARCHAR(30)`, `VARCHAR(300)` | |
| `BODEGA_HABITUAL_ID` | `BIGINT` | sin FK: la bodega vive en `agro_catalog` |
```

`docs/00-decisiones.md`, al final:
```markdown
---

## D8 — Autorización en dos capas: rol del token y cuenta aprobada

**Decidido:** el rol sigue viniendo del claim `roles` de Azure AD (lo exige la
pauta: *"leer roles y scopes desde los claims del token"*). Encima, el BFF
exige que la cuenta esté `ACTIVA` en `ms-agrotrack-users`.

**Por qué:** sin la segunda capa, dar de alta o quitar el acceso a alguien
obliga a entrar al portal de Azure. Con ella, el administrador lo hace desde
la web y un despedido deja de entrar aunque su cuenta de Microsoft siga viva.

**Coste:** una llamada a `users` por usuario por minuto (caché de 60 s en el
BFF). Si `users` cae, el BFF responde 503: ante la duda no se deja pasar.
```

`docs/10-checklist-demo.md`, tras la tabla "La autorización por rol se ve sola":
```markdown
**La aprobación de cuentas** (segunda capa, además del rol):

1. Entra primero con tu cuenta de administrador: al ser el primer admin queda
   **activa sola** (`aprobado_por = sistema`).
2. Entra con **Productor Demo** en otra ventana de incógnito: cae en
   **"Tu cuenta espera aprobación"** aunque su token trae `CLIENTE`.
   En Network, cualquier `/api/**` le da `403` con `codigo: CUENTA_PENDIENTE`.
3. Como admin, en **Usuarios** aparece pendiente: **Aprobar**.
4. El productor pulsa **Volver a comprobar** y entra.

Lo que conviene decir: *el rol lo pone Azure; si la cuenta puede usar el
sistema lo decide AgroTrack*.
```

- [ ] **Step 8: Commit**

```bash
cd /c/Users/deint/Desktop/AgroTrack
git add infra/apps/compose.yml infra/.env.aws.example infra/aws/crear.ps1 infra/local/smoke.ps1 infra/local/azure-local.ps1 infra/aws/smoke-aws.ps1 README.md docs/00-decisiones.md docs/06-modelo-de-datos.md docs/10-checklist-demo.md
git commit -m "feat(infra): users en compose y smoke que prueba bloqueo y aprobacion"
```

---

### Task 9: Frontend — modelos, API, `CuentaService` y `estadoGuard`

Todo lo de frontend se hace en `C:\Users\deint\Desktop\AgroTrack\frontend-agrotrack` y se commitea en **ese** repo.

**Files:**
- Modify: `frontend-agrotrack/src/app/core/models.ts`
- Modify: `frontend-agrotrack/src/app/core/api/api.service.ts`
- Create: `frontend-agrotrack/src/app/core/auth/cuenta.service.ts`
- Modify: `frontend-agrotrack/src/app/core/auth/guards.ts`
- Test: `frontend-agrotrack/src/app/core/auth/cuenta.service.spec.ts`, `frontend-agrotrack/src/app/core/auth/guards.spec.ts`

**Interfaces:**
- Consumes: `GET /api/me` → `Yo` (Task 7), `/api/users/**` (Task 5 vía BFF).
- Produces:
  - `type EstadoCuenta = 'PENDIENTE' | 'ACTIVO' | 'RECHAZADO' | 'INACTIVO'`
  - `interface Yo extends Usuario { usuarioId: number | null; estado: EstadoCuenta; motivo: string | null; primerIngreso: string | null }`
  - `interface PerfilProductor { rut; razonSocial; telefono: string | null; direccion: string | null; bodegaHabitualId: number | null }`
  - `interface CuentaUsuario { id; azureOid; email; nombre; estado; rolUltimoToken: Rol | null; motivo; primerIngreso; ultimoIngreso; aprobadoPor; aprobadoEn; perfil: PerfilProductor | null }`
  - `Problema.codigo?: string`
  - `ApiService.me(): Promise<Yo>`, `usuarios(estado?)`, `cambiarEstadoCuenta(id, {estado, motivo})`, `miCuenta()`, `guardarFicha(id, ficha)`
  - `CuentaService { yo: Signal<Yo|null>; error: Signal<string|null>; cargar(): Promise<Yo|null>; recargar(): Promise<Yo|null>; limpiar(): void }`
  - `estadoGuard: CanActivateFn` → `true` o `UrlTree('/pendiente')`

- [ ] **Step 1: Tipos** — añadir al final de `core/models.ts` y el campo `codigo` en `Problema`:

```ts
/** Estado de la cuenta en ms-agrotrack-users. Solo ACTIVO usa el sistema. */
export type EstadoCuenta = 'PENDIENTE' | 'ACTIVO' | 'RECHAZADO' | 'INACTIVO';

/** Respuesta de /api/me: identidad del token + estado de la cuenta. */
export interface Yo extends Usuario {
  usuarioId: number | null;
  estado: EstadoCuenta;
  motivo: string | null;
  primerIngreso: string | null;
}

export interface PerfilProductor {
  rut: string;
  razonSocial: string;
  telefono: string | null;
  direccion: string | null;
  bodegaHabitualId: number | null;
}

export interface CuentaUsuario {
  id: number;
  azureOid: string;
  email: string | null;
  nombre: string | null;
  estado: EstadoCuenta;
  /** Informativo: el rol con el que entro la ultima vez. */
  rolUltimoToken: Rol | null;
  motivo: string | null;
  primerIngreso: string;
  ultimoIngreso: string;
  aprobadoPor: string | null;
  aprobadoEn: string | null;
  perfil: PerfilProductor | null;
}
```

En `interface Problema`, tras `motivo?: string;`:
```ts
  /** Codigo de negocio, p. ej. CUENTA_PENDIENTE o AUTO_DESACTIVACION. */
  codigo?: string;
```

- [ ] **Step 2: `ApiService`**

Añadir `CuentaUsuario, EstadoCuenta, PerfilProductor, Yo` al import de `../models` (y quitar `Usuario` si deja de usarse). Reemplazar `me()` y añadir la sección de usuarios:

```ts
  // ---- identidad ----
  /** Ademas de decir quien soy, registra la cuenta la primera vez (lo hace el BFF). */
  me(): Promise<Yo> {
    return this.get<Yo>('/api/me');
  }

  // ---- usuarios ----
  usuarios(estado?: EstadoCuenta): Promise<CuentaUsuario[]> {
    return this.get<CuentaUsuario[]>('/api/users', { estado });
  }

  cambiarEstadoCuenta(id: number, body: { estado: EstadoCuenta; motivo?: string | null }): Promise<CuentaUsuario> {
    return this.put<CuentaUsuario>(`/api/users/${id}/estado`, body);
  }

  miCuenta(): Promise<CuentaUsuario> {
    return this.get<CuentaUsuario>('/api/users/me');
  }

  guardarFicha(id: number, ficha: PerfilProductor): Promise<PerfilProductor> {
    return this.put<PerfilProductor>(`/api/users/${id}/perfil`, ficha);
  }
```

En `aProblema`, dentro del objeto devuelto para `HttpErrorResponse`, tras `motivo: cuerpo.motivo,`:
```ts
        codigo: cuerpo.codigo,
```

- [ ] **Step 3: Escribir los tests**

`core/auth/cuenta.service.spec.ts`:
```ts
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../api/api.service';
import { Yo } from '../models';
import { CuentaService } from './cuenta.service';

const yo = (estado: Yo['estado']): Yo => ({
  userId: 'u1', nombre: 'Diego', roles: ['CLIENTE'], usuarioId: 1, estado, motivo: null, primerIngreso: '2026-09-15T10:00:00Z',
});

describe('CuentaService', () => {
  const me = vi.fn();
  let cuenta: CuentaService;

  beforeEach(() => {
    me.mockReset();
    TestBed.configureTestingModule({ providers: [{ provide: ApiService, useValue: { me } }] });
    cuenta = TestBed.inject(CuentaService);
  });

  it('dos guardas pidiendo a la vez hacen UNA sola llamada a /api/me', async () => {
    me.mockResolvedValue(yo('ACTIVO'));
    const [a, b] = await Promise.all([cuenta.cargar(), cuenta.cargar()]);
    expect(me).toHaveBeenCalledTimes(1);
    expect(a?.estado).toBe('ACTIVO');
    expect(b).toBe(a);
  });

  it('una vez cargada no vuelve a preguntar', async () => {
    me.mockResolvedValue(yo('PENDIENTE'));
    await cuenta.cargar();
    await cuenta.cargar();
    expect(me).toHaveBeenCalledTimes(1);
    expect(cuenta.yo()?.estado).toBe('PENDIENTE');
  });

  it('recargar pregunta de nuevo (botón "Volver a comprobar")', async () => {
    me.mockResolvedValueOnce(yo('PENDIENTE')).mockResolvedValueOnce(yo('ACTIVO'));
    await cuenta.cargar();
    expect((await cuenta.recargar())?.estado).toBe('ACTIVO');
  });

  it('si el BFF falla deja el error y null, y el siguiente intento reintenta', async () => {
    me.mockRejectedValueOnce({ status: 503, detail: 'No se pudo verificar tu cuenta' }).mockResolvedValueOnce(yo('ACTIVO'));
    expect(await cuenta.cargar()).toBeNull();
    expect(cuenta.error()).toBe('No se pudo verificar tu cuenta');
    expect((await cuenta.cargar())?.estado).toBe('ACTIVO');
    expect(cuenta.error()).toBeNull();
  });

  it('limpiar olvida la cuenta (al cerrar sesión)', async () => {
    me.mockResolvedValue(yo('ACTIVO'));
    await cuenta.cargar();
    cuenta.limpiar();
    expect(cuenta.yo()).toBeNull();
  });
});
```

`core/auth/guards.spec.ts`:
```ts
import { TestBed } from '@angular/core/testing';
import { Router, UrlTree, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { EstadoCuenta, Yo } from '../models';
import { CuentaService } from './cuenta.service';
import { estadoGuard } from './guards';

describe('estadoGuard', () => {
  let respuesta: Yo | null;

  beforeEach(() => {
    respuesta = null;
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: CuentaService, useValue: { cargar: async () => respuesta } }],
    });
  });

  const ejecutar = () =>
    TestBed.runInInjectionContext(() => estadoGuard({} as never, {} as never)) as Promise<boolean | UrlTree>;

  const conEstado = (estado: EstadoCuenta): Yo => ({
    userId: 'u1', nombre: 'Diego', roles: ['CLIENTE'], usuarioId: 1, estado, motivo: null, primerIngreso: null,
  });

  it('deja pasar una cuenta ACTIVA', async () => {
    respuesta = conEstado('ACTIVO');
    expect(await ejecutar()).toBe(true);
  });

  it.each(['PENDIENTE', 'RECHAZADO', 'INACTIVO'] as EstadoCuenta[])('manda una cuenta %s a /pendiente', async (estado) => {
    respuesta = conEstado(estado);
    const r = await ejecutar();
    expect(TestBed.inject(Router).serializeUrl(r as UrlTree)).toBe('/pendiente');
  });

  it('si no se pudo verificar la cuenta, tampoco deja pasar', async () => {
    const r = await ejecutar();
    expect(TestBed.inject(Router).serializeUrl(r as UrlTree)).toBe('/pendiente');
  });
});
```

- [ ] **Step 4: Correr y ver que falla**

Run: `cd /c/Users/deint/Desktop/AgroTrack/frontend-agrotrack && npx ng test --watch=false`
Expected: FAIL — no existen `cuenta.service` ni `estadoGuard`.

- [ ] **Step 5: Implementar**

`core/auth/cuenta.service.ts`:
```ts
import { Injectable, inject, signal } from '@angular/core';
import { ApiService } from '../api/api.service';
import { Problema, Yo } from '../models';

/**
 * Estado de la cuenta del usuario en AgroTrack. El rol lo trae el token;
 * si puede usar el sistema lo decide ms-agrotrack-users, y se sabe
 * preguntando a /api/me (que ademas registra la cuenta la primera vez).
 */
@Injectable({ providedIn: 'root' })
export class CuentaService {
  private readonly api = inject(ApiService);

  private readonly _yo = signal<Yo | null>(null);
  readonly yo = this._yo.asReadonly();

  private readonly _error = signal<string | null>(null);
  readonly error = this._error.asReadonly();

  private enCurso: Promise<Yo | null> | null = null;

  /** Una sola llamada aunque varias guardas pregunten a la vez. */
  cargar(): Promise<Yo | null> {
    const actual = this._yo();
    if (actual) return Promise.resolve(actual);
    if (!this.enCurso) {
      this.enCurso = this.api
        .me()
        .then((yo) => {
          this._yo.set(yo);
          this._error.set(null);
          return yo;
        })
        .catch((e: Problema) => {
          this._error.set(e?.detail ?? 'No se pudo verificar tu cuenta');
          return null;
        })
        .finally(() => (this.enCurso = null));
    }
    return this.enCurso;
  }

  recargar(): Promise<Yo | null> {
    this._yo.set(null);
    return this.cargar();
  }

  limpiar(): void {
    this._yo.set(null);
    this._error.set(null);
  }
}
```

En `core/auth/guards.ts`, añadir el import `import { CuentaService } from './cuenta.service';` y:
```ts
/**
 * Con sesion pero sin cuenta ACTIVA -> /pendiente. Espejo del filtro del
 * BFF: sin esta guarda, un pendiente veria el menu y un 403 en cada pantalla.
 */
export const estadoGuard: CanActivateFn = async () => {
  // inject() solo vale antes del primer await
  const cuenta = inject(CuentaService);
  const router = inject(Router);
  const yo = await cuenta.cargar();
  return yo?.estado === 'ACTIVO' ? true : router.createUrlTree(['/pendiente']);
};
```

- [ ] **Step 6: Correr y ver que pasa**

Run: `npx ng test --watch=false`
Expected: PASS (los specs existentes + 5 de `CuentaService` + 5 de `estadoGuard`).

- [ ] **Step 7: Commit (repo del frontend)**

```bash
git add src/app/core
git commit -m "feat(cuenta): estado de la cuenta desde /api/me y estadoGuard"
```

---

### Task 10: Frontend — pantalla `/pendiente`, rutas y menú

**Files:**
- Create: `frontend-agrotrack/src/app/core/usuarios.ts`
- Create: `frontend-agrotrack/src/app/pages/pendiente/pendiente.ts`
- Modify: `frontend-agrotrack/src/app/app.routes.ts`
- Modify: `frontend-agrotrack/src/app/shell/shell.ts`
- Test: `frontend-agrotrack/src/app/pages/pendiente/pendiente.spec.ts`

**Interfaces:**
- Consumes: `CuentaService` (Task 9), `AuthService.logout()` (existente).
- Produces:
  - `core/usuarios.ts`: `ETIQUETA_CUENTA: Record<EstadoCuenta, string>`, `MENSAJE_CUENTA: Record<EstadoCuenta, string>`
  - componente `Pendiente` en `/pendiente`
  - rutas `/usuarios` y `/mi-perfil` (sus componentes llegan en Tasks 11 y 12; hasta entonces **no** se añaden)

- [ ] **Step 1: Textos compartidos** — `core/usuarios.ts`:

```ts
import { EstadoCuenta } from './models';

/**
 * Espejo del ciclo de vida de la cuenta (ms-agrotrack-users). La UI lo usa
 * para mostrar y habilitar botones; la verdad la tiene el backend.
 */
export const ETIQUETA_CUENTA: Record<EstadoCuenta, string> = {
  PENDIENTE: 'Pendiente',
  ACTIVO: 'Activa',
  RECHAZADO: 'Rechazada',
  INACTIVO: 'Desactivada',
};

/** Los mismos textos que el 403 del BFF. */
export const MENSAJE_CUENTA: Record<EstadoCuenta, string> = {
  PENDIENTE: 'Tu cuenta espera aprobación',
  ACTIVO: 'Tu cuenta está activa',
  RECHAZADO: 'Tu solicitud fue rechazada',
  INACTIVO: 'Tu cuenta fue desactivada',
};
```

- [ ] **Step 2: Escribir el test de la pantalla**

`pages/pendiente/pendiente.spec.ts`:
```ts
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthService } from '../../core/auth/auth.service';
import { CuentaService } from '../../core/auth/cuenta.service';
import { Yo } from '../../core/models';
import { Pendiente } from './pendiente';

describe('Pendiente', () => {
  const yo = signal<Yo | null>(null);
  const navigate = vi.fn();
  const recargar = vi.fn();
  const logout = vi.fn(async () => {});

  const cuenta = (estado: Yo['estado'], motivo: string | null = null): Yo => ({
    userId: 'u1', nombre: 'Productor Demo', email: 'productor@mish.cl', roles: ['CLIENTE'],
    usuarioId: 4, estado, motivo, primerIngreso: '2026-09-15T10:00:00Z',
  });

  beforeEach(() => {
    navigate.mockClear();
    recargar.mockReset();
    yo.set(cuenta('PENDIENTE'));
    TestBed.configureTestingModule({
      providers: [
        { provide: CuentaService, useValue: { yo, error: signal(null), cargar: async () => yo(), recargar, limpiar: vi.fn() } },
        { provide: AuthService, useValue: { logout } },
        { provide: Router, useValue: { navigate } },
      ],
    });
  });

  it('muestra el mensaje del estado y el correo', async () => {
    const f = TestBed.createComponent(Pendiente);
    f.detectChanges();
    await f.whenStable();
    const texto = (f.nativeElement as HTMLElement).textContent ?? '';
    expect(texto).toContain('Tu cuenta espera aprobación');
    expect(texto).toContain('productor@mish.cl');
  });

  it('muestra el motivo de un rechazo', async () => {
    yo.set(cuenta('RECHAZADO', 'RUT no coincide'));
    const f = TestBed.createComponent(Pendiente);
    f.detectChanges();
    await f.whenStable();
    expect((f.nativeElement as HTMLElement).textContent).toContain('RUT no coincide');
  });

  it('"Volver a comprobar" entra al panel si ya fue aprobada', async () => {
    recargar.mockResolvedValue(cuenta('ACTIVO'));
    const f = TestBed.createComponent(Pendiente);
    f.detectChanges();
    await f.componentInstance.comprobar();
    expect(navigate).toHaveBeenCalledWith(['/dashboard'], { replaceUrl: true });
  });

  it('"Volver a comprobar" se queda si sigue pendiente', async () => {
    recargar.mockResolvedValue(cuenta('PENDIENTE'));
    const f = TestBed.createComponent(Pendiente);
    f.detectChanges();
    await f.componentInstance.comprobar();
    expect(navigate).not.toHaveBeenCalledWith(['/dashboard'], { replaceUrl: true });
  });
});
```

- [ ] **Step 3: Correr y ver que falla**

Run: `npx ng test --watch=false`
Expected: FAIL — no existe `./pendiente`.

- [ ] **Step 4: Implementar la pantalla** — `pages/pendiente/pendiente.ts`:

```ts
import { DatePipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { CuentaService } from '../../core/auth/cuenta.service';
import { MENSAJE_CUENTA } from '../../core/usuarios';

/**
 * /pendiente: para quien entro con Microsoft pero no tiene la cuenta activa.
 * Sin menu a proposito: la unica salida es comprobar de nuevo o cerrar sesion.
 */
@Component({
  selector: 'app-pendiente',
  imports: [DatePipe],
  template: `
    <main class="pendiente">
      <section class="card">
        <h1>{{ titulo() }}</h1>
        @if (cuenta.yo(); as yo) {
          <p>Entraste como <b>{{ yo.email ?? yo.nombre }}</b>@if (yo.primerIngreso) {, solicitud del {{ yo.primerIngreso | date: 'medium' }}}.</p>
          @if (yo.estado === 'PENDIENTE') {
            <p>Un administrador de AgroTrack debe aprobar tu cuenta antes de que puedas usar el sistema.</p>
          }
          @if (yo.motivo) { <p class="alerta alerta--error">Motivo: {{ yo.motivo }}</p> }
        } @else {
          <p class="alerta alerta--error">{{ cuenta.error() ?? 'No se pudo verificar tu cuenta' }}</p>
        }
        <div class="acciones">
          <button class="btn btn--primario" (click)="comprobar()" [disabled]="comprobando()">
            {{ comprobando() ? 'Comprobando…' : 'Volver a comprobar' }}
          </button>
          <button class="btn btn--ghost" (click)="salir()">Salir</button>
        </div>
      </section>
    </main>
  `,
  styles: `
    .pendiente { min-height: 100vh; display: grid; place-items: center; padding: 1rem; background: var(--gris-100); }
    .card { max-width: 480px; width: 100%; }
    .acciones { display: flex; gap: .5rem; margin-top: 1rem; }
  `,
})
export class Pendiente implements OnInit {
  readonly cuenta = inject(CuentaService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  readonly comprobando = signal(false);
  readonly titulo = computed(() => {
    const yo = this.cuenta.yo();
    return yo ? MENSAJE_CUENTA[yo.estado] : 'No se pudo verificar tu cuenta';
  });

  async ngOnInit() {
    // Si llega aqui con la cuenta ya activa (p. ej. un marcador), no hay nada que esperar.
    const yo = await this.cuenta.cargar();
    if (yo?.estado === 'ACTIVO') this.router.navigate(['/dashboard'], { replaceUrl: true });
  }

  async comprobar() {
    this.comprobando.set(true);
    try {
      const yo = await this.cuenta.recargar();
      if (yo?.estado === 'ACTIVO') this.router.navigate(['/dashboard'], { replaceUrl: true });
    } finally {
      this.comprobando.set(false);
    }
  }

  async salir() {
    this.cuenta.limpiar();
    await this.auth.logout();
    this.router.navigate(['/login']);
  }
}
```

Nota para el test `se queda si sigue pendiente`: `ngOnInit` también llama a `navigate` solo si el estado es `ACTIVO`; con `yo` en `PENDIENTE` no navega, por eso la aserción es `not.toHaveBeenCalledWith`.

- [ ] **Step 5: Rutas** — en `app.routes.ts`, importar `estadoGuard` junto a los otros:
```ts
import { authGuard, estadoGuard, roleGuard } from './core/auth/guards';
```
Tras la ruta `login`:
```ts
  // Con sesion pero sin cuenta activa. Fuera del shell: sin menu.
  { path: 'pendiente', canActivate: [authGuard], loadComponent: () => import('./pages/pendiente/pendiente').then((m) => m.Pendiente) },
```
Y en la ruta del shell: `canActivate: [authGuard],` → `canActivate: [authGuard, estadoGuard],`

- [ ] **Step 6: Menú** — en `shell/shell.ts`:

Import: `import { CuentaService } from '../core/auth/cuenta.service';`

En `todos`, tras `Auditoría`:
```ts
    { ruta: '/usuarios', texto: 'Usuarios', roles: ['ADMIN'] },
    { ruta: '/mi-perfil', texto: 'Mi ficha', roles: ['CLIENTE'] },
```
Campo y `salir()`:
```ts
  private readonly cuenta = inject(CuentaService);

  async salir() {
    // Si otra persona entra en este navegador, no debe heredar el estado de esta cuenta.
    this.cuenta.limpiar();
    await this.auth.logout();
    this.router.navigate(['/login']);
  }
```

Los enlaces `/usuarios` y `/mi-perfil` caen en `**` → `dashboard` hasta que existan sus rutas (Tasks 11 y 12). No rompe nada.

- [ ] **Step 7: Correr tests y build**

Run: `npx ng test --watch=false && npx ng build`
Expected: tests PASS (4 nuevos de `Pendiente`); build sin errores.

- [ ] **Step 8: Commit**

```bash
git add src/app
git commit -m "feat(cuenta): pantalla de cuenta pendiente y guarda en el shell"
```

---

### Task 11: Frontend — administración de usuarios (`/usuarios`)

**Files:**
- Modify: `frontend-agrotrack/src/app/core/usuarios.ts`
- Create: `frontend-agrotrack/src/app/pages/usuarios/usuarios.ts`
- Modify: `frontend-agrotrack/src/app/app.routes.ts`
- Test: `frontend-agrotrack/src/app/core/usuarios.spec.ts`, `frontend-agrotrack/src/app/pages/usuarios/usuarios.spec.ts`

**Interfaces:**
- Consumes: `ApiService.usuarios/cambiarEstadoCuenta/guardarFicha/bodegas` (Task 9 y existente), `ETIQUETA_CUENTA` (Task 10), `ROL_ETIQUETA` (existente en `core/estados.ts`), `AuthService.user()`.
- Produces (en `core/usuarios.ts`, también los usa Task 12):
  - `interface AccionCuenta { destino: EstadoCuenta; texto: string; exigeMotivo: boolean; peligro: boolean }`
  - `SIGUIENTES_CUENTA: Record<EstadoCuenta, EstadoCuenta[]>`
  - `accionesPara(estado: EstadoCuenta, esUnoMismo: boolean): AccionCuenta[]`
  - `type FichaForm = { rut: string; razonSocial: string; telefono: string; direccion: string; bodegaHabitualId: number | null }`
  - `fichaDesde(p: PerfilProductor | null): FichaForm`, `normalizarFicha(f: FichaForm): PerfilProductor`

- [ ] **Step 1: Escribir los tests de la lógica**

`core/usuarios.spec.ts`:
```ts
import { describe, expect, it } from 'vitest';
import { EstadoCuenta } from './models';
import { SIGUIENTES_CUENTA, accionesPara, fichaDesde, normalizarFicha } from './usuarios';

const destinos = (estado: EstadoCuenta, yo = false) => accionesPara(estado, yo).map((a) => a.destino);

describe('acciones sobre una cuenta', () => {
  it('pendiente: aprobar o rechazar (rechazar exige motivo)', () => {
    const a = accionesPara('PENDIENTE', false);
    expect(a.map((x) => [x.texto, x.destino, x.exigeMotivo])).toEqual([
      ['Aprobar', 'ACTIVO', false],
      ['Rechazar', 'RECHAZADO', true],
    ]);
  });

  it('activa de otro: desactivar con motivo', () => {
    expect(accionesPara('ACTIVO', false).map((x) => [x.texto, x.exigeMotivo])).toEqual([['Desactivar', true]]);
  });

  it('mi propia cuenta activa: ninguna acción (no me puedo desactivar)', () => {
    expect(accionesPara('ACTIVO', true)).toEqual([]);
  });

  it('desactivada: reactivar; rechazada: reconsiderar', () => {
    expect(accionesPara('INACTIVO', false).map((x) => x.texto)).toEqual(['Reactivar']);
    expect(accionesPara('RECHAZADO', false).map((x) => x.texto)).toEqual(['Reconsiderar']);
  });

  it('ningún botón ofrece una transición que el backend rechazaría', () => {
    for (const estado of Object.keys(SIGUIENTES_CUENTA) as EstadoCuenta[]) {
      for (const d of destinos(estado)) expect(SIGUIENTES_CUENTA[estado]).toContain(d);
    }
  });
});

describe('ficha del productor', () => {
  it('normaliza antes de enviar: recorta, RUT en mayúscula y vacíos a null', () => {
    expect(
      normalizarFicha({ rut: ' 12345678-k ', razonSocial: ' Agrícola Sur ', telefono: '  ', direccion: '', bodegaHabitualId: null }),
    ).toEqual({ rut: '12345678-K', razonSocial: 'Agrícola Sur', telefono: null, direccion: null, bodegaHabitualId: null });
  });

  it('una bodega elegida en el select llega como número', () => {
    const f = { ...fichaDesde(null), rut: '1-9', razonSocial: 'X', bodegaHabitualId: '3' as unknown as number };
    expect(normalizarFicha(f).bodegaHabitualId).toBe(3);
  });

  it('fichaDesde(null) da un formulario vacío editable', () => {
    expect(fichaDesde(null)).toEqual({ rut: '', razonSocial: '', telefono: '', direccion: '', bodegaHabitualId: null });
  });
});
```

- [ ] **Step 2: Correr y ver que falla**

Run: `npx ng test --watch=false`
Expected: FAIL — `accionesPara` no existe.

- [ ] **Step 3: Implementar la lógica** — añadir a `core/usuarios.ts` (import ampliado a `EstadoCuenta, PerfilProductor`):

```ts
/** Tabla de ms-agrotrack-users (MaquinaEstadosUsuario). */
export const SIGUIENTES_CUENTA: Record<EstadoCuenta, EstadoCuenta[]> = {
  PENDIENTE: ['ACTIVO', 'RECHAZADO'],
  ACTIVO: ['INACTIVO'],
  INACTIVO: ['ACTIVO'],
  RECHAZADO: ['ACTIVO'],
};

export interface AccionCuenta {
  destino: EstadoCuenta;
  texto: string;
  exigeMotivo: boolean;
  peligro: boolean;
}

const APROBAR: AccionCuenta = { destino: 'ACTIVO', texto: 'Aprobar', exigeMotivo: false, peligro: false };
const RECHAZAR: AccionCuenta = { destino: 'RECHAZADO', texto: 'Rechazar', exigeMotivo: true, peligro: true };
const DESACTIVAR: AccionCuenta = { destino: 'INACTIVO', texto: 'Desactivar', exigeMotivo: true, peligro: true };
const REACTIVAR: AccionCuenta = { destino: 'ACTIVO', texto: 'Reactivar', exigeMotivo: false, peligro: false };
const RECONSIDERAR: AccionCuenta = { destino: 'ACTIVO', texto: 'Reconsiderar', exigeMotivo: false, peligro: false };

/** Botones de cada fila. Mi propia cuenta activa no ofrece desactivarse: el backend daria 409. */
export function accionesPara(estado: EstadoCuenta, esUnoMismo: boolean): AccionCuenta[] {
  switch (estado) {
    case 'PENDIENTE':
      return [APROBAR, RECHAZAR];
    case 'ACTIVO':
      return esUnoMismo ? [] : [DESACTIVAR];
    case 'INACTIVO':
      return [REACTIVAR];
    case 'RECHAZADO':
      return [RECONSIDERAR];
  }
}

export type FichaForm = { rut: string; razonSocial: string; telefono: string; direccion: string; bodegaHabitualId: number | null };

export function fichaDesde(p: PerfilProductor | null): FichaForm {
  return {
    rut: p?.rut ?? '',
    razonSocial: p?.razonSocial ?? '',
    telefono: p?.telefono ?? '',
    direccion: p?.direccion ?? '',
    bodegaHabitualId: p?.bodegaHabitualId ?? null,
  };
}

/** El select entrega strings y los inputs espacios: se limpia aqui y no en cada pantalla. */
export function normalizarFicha(f: FichaForm): PerfilProductor {
  const texto = (v: string) => (v ?? '').trim() || null;
  return {
    rut: (f.rut ?? '').trim().toUpperCase(),
    razonSocial: (f.razonSocial ?? '').trim(),
    telefono: texto(f.telefono),
    direccion: texto(f.direccion),
    bodegaHabitualId: f.bodegaHabitualId ? Number(f.bodegaHabitualId) : null,
  };
}
```

- [ ] **Step 4: Correr y ver que pasa**

Run: `npx ng test --watch=false`
Expected: PASS (8 nuevos).

- [ ] **Step 5: Escribir el test de la pantalla**

`pages/usuarios/usuarios.spec.ts`:
```ts
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api/api.service';
import { AuthService } from '../../core/auth/auth.service';
import { CuentaUsuario, EstadoCuenta } from '../../core/models';
import { Usuarios } from './usuarios';

const fila = (id: number, oid: string, estado: EstadoCuenta, nombre: string): CuentaUsuario => ({
  id, azureOid: oid, email: `${oid}@mish.cl`, nombre, estado, rolUltimoToken: 'CLIENTE', motivo: null,
  primerIngreso: '2026-09-15T10:00:00Z', ultimoIngreso: '2026-09-15T10:00:00Z', aprobadoPor: null, aprobadoEn: null, perfil: null,
});

describe('Usuarios (administración)', () => {
  const api = { usuarios: vi.fn(), cambiarEstadoCuenta: vi.fn(), guardarFicha: vi.fn(), bodegas: vi.fn() };

  beforeEach(() => {
    Object.values(api).forEach((f) => f.mockReset());
    api.bodegas.mockResolvedValue([]);
    api.usuarios.mockResolvedValue([fila(1, 'yo', 'ACTIVO', 'Diego'), fila(2, 'p', 'PENDIENTE', 'Productor Demo')]);
    api.cambiarEstadoCuenta.mockResolvedValue(fila(2, 'p', 'ACTIVO', 'Productor Demo'));
    TestBed.configureTestingModule({
      providers: [
        { provide: ApiService, useValue: api },
        { provide: AuthService, useValue: { user: signal({ userId: 'yo', nombre: 'Diego', roles: ['ADMIN'] }), hasRole: () => true } },
      ],
    });
  });

  async function montar() {
    const f = TestBed.createComponent(Usuarios);
    f.detectChanges();
    await f.whenStable();
    f.detectChanges();
    return f;
  }

  const botones = (el: HTMLElement) => Array.from(el.querySelectorAll('button')).map((b) => b.textContent?.trim());

  it('empieza mostrando las pendientes', async () => {
    await montar();
    expect(api.usuarios).toHaveBeenCalledWith('PENDIENTE');
  });

  it('una pendiente ofrece Aprobar y Rechazar; mi cuenta activa no ofrece Desactivar', async () => {
    const f = await montar();
    const b = botones(f.nativeElement);
    expect(b).toContain('Aprobar');
    expect(b).toContain('Rechazar');
    expect(b).not.toContain('Desactivar');
  });

  it('aprobar llama al backend sin motivo y recarga', async () => {
    const f = await montar();
    await f.componentInstance.ejecutar(f.componentInstance.lista()[1], { destino: 'ACTIVO', texto: 'Aprobar', exigeMotivo: false, peligro: false });
    expect(api.cambiarEstadoCuenta).toHaveBeenCalledWith(2, { estado: 'ACTIVO', motivo: null });
    expect(api.usuarios).toHaveBeenCalledTimes(2);
  });

  it('rechazar sin escribir motivo no llama al backend', async () => {
    const f = await montar();
    const c = f.componentInstance;
    c.iniciar(c.lista()[1], { destino: 'RECHAZADO', texto: 'Rechazar', exigeMotivo: true, peligro: true });
    await c.confirmar();
    expect(api.cambiarEstadoCuenta).not.toHaveBeenCalled();
    expect(c.errorAccion()).toBe('Escribe el motivo');
  });
});
```

- [ ] **Step 6: Implementar la pantalla** — `pages/usuarios/usuarios.ts`:

```ts
import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api/api.service';
import { AuthService } from '../../core/auth/auth.service';
import { ROL_ETIQUETA } from '../../core/estados';
import { Bodega, CuentaUsuario, EstadoCuenta, Problema } from '../../core/models';
import { AccionCuenta, ETIQUETA_CUENTA, FichaForm, accionesPara, fichaDesde, normalizarFicha } from '../../core/usuarios';

type Filtro = EstadoCuenta | '';

/**
 * /usuarios (ADMIN): aprobar, rechazar, desactivar y reactivar cuentas, y
 * editar la ficha del productor. El rol no se cambia aqui: vive en Azure AD.
 */
@Component({
  selector: 'app-usuarios',
  imports: [FormsModule, DatePipe],
  template: `
    <h1>Usuarios</h1>
    <p class="nota">El rol lo asigna Azure AD; aquí se decide si la cuenta puede usar AgroTrack.</p>
    @if (error()) { <p class="alerta alerta--error">{{ error() }}</p> }

    <div class="filtros">
      @for (f of filtros; track f.valor) {
        <button class="btn" [class.btn--primario]="filtro() === f.valor" [class.btn--ghost]="filtro() !== f.valor"
          (click)="filtrar(f.valor)">{{ f.texto }}</button>
      }
    </div>

    <section class="card tabla-wrap">
      <table class="tabla">
        <thead><tr><th>Nombre</th><th>Correo</th><th>Rol (último ingreso)</th><th>Estado</th><th>Solicitó</th><th></th></tr></thead>
        <tbody>
          @for (u of lista(); track u.id) {
            <tr>
              <td>{{ u.nombre ?? '—' }}</td>
              <td>{{ u.email ?? '—' }}</td>
              <td>{{ rol(u) }}</td>
              <td><span class="estado estado--{{ u.estado.toLowerCase() }}">{{ etiqueta[u.estado] }}</span>
                @if (u.motivo) { <small class="motivo">{{ u.motivo }}</small> }</td>
              <td>{{ u.primerIngreso | date: 'short' }}</td>
              <td class="acciones">
                @for (a of acciones(u); track a.texto) {
                  <button class="btn" [class.btn--peligro]="a.peligro" [class.btn--sec]="!a.peligro" (click)="iniciar(u, a)">{{ a.texto }}</button>
                }
                @if (u.rolUltimoToken === 'CLIENTE' || u.perfil) {
                  <button class="btn btn--ghost" (click)="abrirFicha(u)">Ficha</button>
                }
              </td>
            </tr>
          } @empty { <tr><td colspan="6" class="vacio">No hay cuentas en este estado.</td></tr> }
        </tbody>
      </table>
    </section>

    @if (enCurso(); as ac) {
      <section class="card form">
        <h3>{{ ac.accion.texto }} a {{ ac.usuario.nombre ?? ac.usuario.email }}</h3>
        <label>Motivo <textarea [(ngModel)]="motivo" name="motivo" maxlength="500" rows="3"></textarea></label>
        @if (errorAccion()) { <p class="alerta alerta--error">{{ errorAccion() }}</p> }
        <div><button class="btn btn--peligro" (click)="confirmar()">{{ ac.accion.texto }}</button>
          <button class="btn btn--ghost" (click)="enCurso.set(null)">Cancelar</button></div>
      </section>
    }

    @if (ficha(); as fi) {
      <form class="card form" (ngSubmit)="guardarFicha()">
        <h3>Ficha de {{ fi.usuario.nombre ?? fi.usuario.email }}</h3>
        <label>RUT <input [(ngModel)]="fi.form.rut" name="rut" required placeholder="12345678-9" maxlength="15" /></label>
        <label>Razón social <input [(ngModel)]="fi.form.razonSocial" name="rs" required maxlength="200" /></label>
        <label>Teléfono <input [(ngModel)]="fi.form.telefono" name="tel" maxlength="30" /></label>
        <label>Dirección <input [(ngModel)]="fi.form.direccion" name="dir" maxlength="300" /></label>
        <label>Bodega habitual
          <select [(ngModel)]="fi.form.bodegaHabitualId" name="bod">
            <option [ngValue]="null">— Sin bodega —</option>
            @for (b of bodegas(); track b.id) { <option [ngValue]="b.id">{{ b.nombre }}</option> }
          </select>
        </label>
        @if (errorFicha()) { <p class="alerta alerta--error">{{ errorFicha() }}</p> }
        <div><button class="btn btn--primario" type="submit">Guardar</button>
          <button class="btn btn--ghost" type="button" (click)="ficha.set(null)">Cancelar</button></div>
      </form>
    }
  `,
  styles: `
    .nota { color: var(--gris-600); margin-top: -.5rem; }
    .filtros { display: flex; gap: .4rem; flex-wrap: wrap; margin: 1rem 0; }
    .acciones { display: flex; gap: .35rem; flex-wrap: wrap; justify-content: flex-end; }
    .estado { padding: .15rem .55rem; border-radius: 999px; font-size: .78rem; font-weight: 600; }
    .estado--pendiente { background: #fff4d6; color: #7a5600; }
    .estado--activo { background: var(--verde-100); color: var(--verde-900); }
    .estado--rechazado { background: #fde2e1; color: #8a1c14; }
    .estado--inactivo { background: var(--gris-100); color: var(--gris-600); }
    .motivo { display: block; color: var(--gris-600); margin-top: .2rem; }
    .form { display: grid; gap: .5rem; margin-top: 1rem; max-width: 520px; }
  `,
})
export class Usuarios implements OnInit {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);

  readonly etiqueta = ETIQUETA_CUENTA;
  readonly filtros: { valor: Filtro; texto: string }[] = [
    { valor: 'PENDIENTE', texto: 'Pendientes' },
    { valor: 'ACTIVO', texto: 'Activas' },
    { valor: 'RECHAZADO', texto: 'Rechazadas' },
    { valor: 'INACTIVO', texto: 'Desactivadas' },
    { valor: '', texto: 'Todas' },
  ];

  readonly filtro = signal<Filtro>('PENDIENTE');
  readonly lista = signal<CuentaUsuario[]>([]);
  readonly bodegas = signal<Bodega[]>([]);
  readonly error = signal('');
  readonly enCurso = signal<{ usuario: CuentaUsuario; accion: AccionCuenta } | null>(null);
  readonly errorAccion = signal('');
  readonly ficha = signal<{ usuario: CuentaUsuario; form: FichaForm } | null>(null);
  readonly errorFicha = signal('');
  motivo = '';

  async ngOnInit() {
    await Promise.all([this.cargar(), this.api.bodegas().then((b) => this.bodegas.set(b)).catch(() => {})]);
  }

  async cargar() {
    this.error.set('');
    try {
      this.lista.set(await this.api.usuarios(this.filtro() || undefined));
    } catch (e) {
      this.error.set((e as Problema).detail);
    }
  }

  async filtrar(f: Filtro) {
    this.filtro.set(f);
    await this.cargar();
  }

  rol = (u: CuentaUsuario) => (u.rolUltimoToken ? ROL_ETIQUETA[u.rolUltimoToken] : 'Sin rol');
  acciones = (u: CuentaUsuario) => accionesPara(u.estado, u.azureOid === this.auth.user()?.userId);

  /** Las acciones sin motivo se ejecutan al tiro; las otras abren el formulario. */
  iniciar(u: CuentaUsuario, a: AccionCuenta) {
    this.errorAccion.set('');
    if (!a.exigeMotivo) {
      void this.ejecutar(u, a);
      return;
    }
    this.motivo = '';
    this.enCurso.set({ usuario: u, accion: a });
  }

  async confirmar() {
    const ac = this.enCurso();
    if (!ac) return;
    if (!this.motivo.trim()) {
      this.errorAccion.set('Escribe el motivo');
      return;
    }
    await this.ejecutar(ac.usuario, ac.accion, this.motivo.trim());
  }

  async ejecutar(u: CuentaUsuario, a: AccionCuenta, motivo: string | null = null) {
    try {
      await this.api.cambiarEstadoCuenta(u.id, { estado: a.destino, motivo });
      this.enCurso.set(null);
      await this.cargar();
    } catch (e) {
      const p = e as Problema;
      if (this.enCurso()) this.errorAccion.set(p.detail);
      else this.error.set(p.detail);
    }
  }

  abrirFicha(u: CuentaUsuario) {
    this.errorFicha.set('');
    this.ficha.set({ usuario: u, form: fichaDesde(u.perfil) });
  }

  async guardarFicha() {
    const fi = this.ficha();
    if (!fi) return;
    this.errorFicha.set('');
    try {
      await this.api.guardarFicha(fi.usuario.id, normalizarFicha(fi.form));
      this.ficha.set(null);
      await this.cargar();
    } catch (e) {
      const p = e as Problema;
      this.errorFicha.set(p.campos ? Object.entries(p.campos).map(([k, v]) => `${k}: ${v}`).join(' · ') : p.detail);
    }
  }
}
```

- [ ] **Step 7: Ruta** — en `app.routes.ts`, dentro de `children`, tras `audit`:
```ts
      {
        path: 'usuarios',
        canActivate: [roleGuard('ADMIN')],
        loadComponent: () => import('./pages/usuarios/usuarios').then((m) => m.Usuarios),
      },
```

- [ ] **Step 8: Correr tests y build**

Run: `npx ng test --watch=false && npx ng build`
Expected: PASS (4 nuevos de `Usuarios`); build sin errores.

- [ ] **Step 9: Commit**

```bash
git add src/app
git commit -m "feat(usuarios): pantalla de administracion de cuentas y fichas"
```

---

### Task 12: Frontend — ficha propia del productor (`/mi-perfil`)

**Files:**
- Create: `frontend-agrotrack/src/app/pages/mi-perfil/mi-perfil.ts`
- Modify: `frontend-agrotrack/src/app/app.routes.ts`
- Test: `frontend-agrotrack/src/app/pages/mi-perfil/mi-perfil.spec.ts`

**Interfaces:**
- Consumes: `ApiService.miCuenta/guardarFicha/bodegas`, `fichaDesde`, `normalizarFicha`, `FichaForm` (Task 11).
- Produces: componente `MiPerfil` en `/mi-perfil` (CLIENTE).

- [ ] **Step 1: Escribir el test**

`pages/mi-perfil/mi-perfil.spec.ts`:
```ts
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api/api.service';
import { CuentaUsuario } from '../../core/models';
import { MiPerfil } from './mi-perfil';

const cuenta: CuentaUsuario = {
  id: 4, azureOid: 'p', email: 'productor@mish.cl', nombre: 'Productor Demo', estado: 'ACTIVO', rolUltimoToken: 'CLIENTE',
  motivo: null, primerIngreso: '2026-09-15T10:00:00Z', ultimoIngreso: '2026-09-15T10:00:00Z', aprobadoPor: 'sistema', aprobadoEn: null,
  perfil: { rut: '12345678-9', razonSocial: 'Agrícola Sur', telefono: null, direccion: null, bodegaHabitualId: null },
};

describe('MiPerfil', () => {
  const api = { miCuenta: vi.fn(), guardarFicha: vi.fn(), bodegas: vi.fn() };

  beforeEach(() => {
    Object.values(api).forEach((f) => f.mockReset());
    api.miCuenta.mockResolvedValue(cuenta);
    api.bodegas.mockResolvedValue([]);
    TestBed.configureTestingModule({ providers: [{ provide: ApiService, useValue: api }] });
  });

  it('carga la ficha existente en el formulario', async () => {
    const f = TestBed.createComponent(MiPerfil);
    f.detectChanges();
    await f.whenStable();
    expect(f.componentInstance.form().rut).toBe('12345678-9');
  });

  it('guarda sobre SU id con la ficha normalizada y avisa', async () => {
    api.guardarFicha.mockResolvedValue(cuenta.perfil);
    const f = TestBed.createComponent(MiPerfil);
    f.detectChanges();
    await f.whenStable();
    f.componentInstance.form.update((x) => ({ ...x, telefono: ' +56 9 1111 2222 ' }));

    await f.componentInstance.guardar();

    expect(api.guardarFicha).toHaveBeenCalledWith(4, expect.objectContaining({ telefono: '+56 9 1111 2222', direccion: null }));
    expect(f.componentInstance.guardado()).toBe(true);
  });

  it('muestra el error de campo que devuelve el backend', async () => {
    api.guardarFicha.mockRejectedValue({ status: 400, detail: 'Datos invalidos', campos: { rut: 'RUT con guion, sin puntos: 12345678-9' } });
    const f = TestBed.createComponent(MiPerfil);
    f.detectChanges();
    await f.whenStable();

    await f.componentInstance.guardar();

    expect(f.componentInstance.error()).toContain('rut: RUT con guion');
  });
});
```

- [ ] **Step 2: Correr y ver que falla**

Run: `npx ng test --watch=false`
Expected: FAIL — no existe `./mi-perfil`.

- [ ] **Step 3: Implementar** — `pages/mi-perfil/mi-perfil.ts`:

```ts
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api/api.service';
import { Bodega, CuentaUsuario, Problema } from '../../core/models';
import { FichaForm, fichaDesde, normalizarFicha } from '../../core/usuarios';

/** /mi-perfil (CLIENTE): el productor mantiene su propia ficha. */
@Component({
  selector: 'app-mi-perfil',
  imports: [FormsModule],
  template: `
    <h1>Mi ficha de productor</h1>
    @if (cuenta(); as c) { <p class="nota">{{ c.nombre }} · {{ c.email }}</p> }

    <form class="card form" (ngSubmit)="guardar()">
      <label>RUT <input [ngModel]="form().rut" (ngModelChange)="campo('rut', $event)" name="rut" required placeholder="12345678-9" maxlength="15" /></label>
      <label>Razón social <input [ngModel]="form().razonSocial" (ngModelChange)="campo('razonSocial', $event)" name="rs" required maxlength="200" /></label>
      <label>Teléfono <input [ngModel]="form().telefono" (ngModelChange)="campo('telefono', $event)" name="tel" maxlength="30" /></label>
      <label>Dirección <input [ngModel]="form().direccion" (ngModelChange)="campo('direccion', $event)" name="dir" maxlength="300" /></label>
      <label>Bodega habitual
        <select [ngModel]="form().bodegaHabitualId" (ngModelChange)="campo('bodegaHabitualId', $event)" name="bod">
          <option [ngValue]="null">— Sin bodega —</option>
          @for (b of bodegas(); track b.id) { <option [ngValue]="b.id">{{ b.nombre }}</option> }
        </select>
      </label>
      @if (error()) { <p class="alerta alerta--error">{{ error() }}</p> }
      @if (guardado()) { <p class="alerta">Ficha guardada.</p> }
      <div><button class="btn btn--primario" type="submit" [disabled]="!cuenta()">Guardar</button></div>
    </form>
  `,
  styles: `
    .nota { color: var(--gris-600); margin-top: -.5rem; }
    .form { display: grid; gap: .5rem; max-width: 520px; }
  `,
})
export class MiPerfil implements OnInit {
  private readonly api = inject(ApiService);

  readonly cuenta = signal<CuentaUsuario | null>(null);
  readonly bodegas = signal<Bodega[]>([]);
  readonly form = signal<FichaForm>(fichaDesde(null));
  readonly error = signal('');
  readonly guardado = signal(false);

  async ngOnInit() {
    try {
      const [c, b] = await Promise.all([this.api.miCuenta(), this.api.bodegas().catch(() => [] as Bodega[])]);
      this.cuenta.set(c);
      this.bodegas.set(b);
      this.form.set(fichaDesde(c.perfil));
    } catch (e) {
      this.error.set((e as Problema).detail);
    }
  }

  campo<K extends keyof FichaForm>(k: K, v: FichaForm[K]) {
    this.guardado.set(false);
    this.form.update((f) => ({ ...f, [k]: v }));
  }

  async guardar() {
    const c = this.cuenta();
    if (!c) return;
    this.error.set('');
    this.guardado.set(false);
    try {
      await this.api.guardarFicha(c.id, normalizarFicha(this.form()));
      this.guardado.set(true);
    } catch (e) {
      const p = e as Problema;
      this.error.set(p.campos ? Object.entries(p.campos).map(([k, v]) => `${k}: ${v}`).join(' · ') : p.detail);
    }
  }
}
```

- [ ] **Step 4: Ruta** — en `app.routes.ts`, dentro de `children`, tras `usuarios`:
```ts
      {
        path: 'mi-perfil',
        canActivate: [roleGuard('CLIENTE')],
        loadComponent: () => import('./pages/mi-perfil/mi-perfil').then((m) => m.MiPerfil),
      },
```

- [ ] **Step 5: Correr tests y build**

Run: `npx ng test --watch=false && npx ng build --configuration production`
Expected: PASS (3 nuevos); build de producción sin errores.

- [ ] **Step 6: Prueba manual en local** (modo dev, tokens de `mint.mjs`)

Con `infra/local/smoke.ps1 -KeepRunning` corriendo:
```bash
node infra/local/jwt/mint.mjs CLIENTE productor-manual
```
`npx ng serve` → pegar el token en el login → debe caer en **"Tu cuenta espera aprobación"**. En otra ventana, token de `node infra/local/jwt/mint.mjs ADMIN admin-smoke` → **Usuarios** → Pendientes → **Aprobar** a `productor-manual`. Volver a la primera ventana → **Volver a comprobar** → entra al panel, ve **Mi ficha** en el menú y guarda un RUT.

- [ ] **Step 7: Commit**

```bash
git add src/app
git commit -m "feat(usuarios): ficha propia del productor"
```

---

### Task 13: Despliegue en AWS y prueba con los usuarios de Azure

Esta tarea necesita a Diego: encender el lab, pegar credenciales e iniciar sesión con cuentas reales. No se ejecuta sin él.

**Files:** ninguno nuevo. Usa `infra/aws/encender.ps1`, `infra/aws/subir.ps1`, `infra/local/init-postgres/02-users.sql`.

**Interfaces:**
- Consumes: todo lo anterior, ya commiteado y con `SMOKE OK` en local (Task 8, Step 6).
- Produces: `at-users` sano en `ec2-apps`; flujo de aprobación demostrable en `https://85v8hc0ry6.execute-api.us-east-1.amazonaws.com`.

- [ ] **Step 1: Subir el código a GitHub**

```bash
cd /c/Users/deint/Desktop/AgroTrack && git push
cd frontend-agrotrack && git push
```

- [ ] **Step 2: Encender AWS** (Diego: *Start Lab* y credenciales en `%USERPROFILE%\.aws\credentials`)

```powershell
cd C:\Users\deint\Desktop\AgroTrack\infra\aws; .\encender.ps1
```
Expected: las tres instancias `running` y `BFF sano (200)`.

- [ ] **Step 3: Crear la base `agro_users` en el Postgres que ya tiene datos**

El init de Docker solo corre con el volumen vacío; en AWS ya existe, así que se aplica el SQL idempotente a mano, **antes** de desplegar:
```bash
ssh -i ~/.ssh/vockey.pem ec2-user@54.84.179.128 'docker exec -i at-postgres psql -U agrotrack -d postgres' < /c/Users/deint/Desktop/AgroTrack/infra/local/init-postgres/02-users.sql
ssh -i ~/.ssh/vockey.pem ec2-user@54.84.179.128 "docker exec at-postgres psql -U agrotrack -d postgres -tAc \"SELECT datname FROM pg_database WHERE datname='agro_users'\""
```
Expected: la segunda línea imprime `agro_users`. (`agrotrack` es el `POSTGRES_USER` que genera `crear.ps1`.)

- [ ] **Step 4: Desplegar el stack `apps`** (sube `infra/.env.aws` con `POSTGRES_USERS_PASSWORD` y el frontend)

```powershell
cd C:\Users\deint\Desktop\AgroTrack\infra\aws; .\subir.ps1 -Ip 54.84.179.128 -Stack apps
```
Expected: termina sin error y `compose ps` muestra `at-users` y `at-bff` **healthy** (la construcción tarda varios minutos).

- [ ] **Step 5: Comprobaciones sin navegador**

```bash
curl -s -o /dev/null -w "%{http_code}\n" https://85v8hc0ry6.execute-api.us-east-1.amazonaws.com/api/me
ssh -i ~/.ssh/vockey.pem ec2-user@54.84.179.128 'docker logs at-users 2>&1 | grep -E "Migrating|Started MsAgrotrackUsers"'
```
Expected: `401`; en el log `Migrating schema "public" to version "1 - users"` y `Started MsAgrotrackUsersApplication`.

El Gateway **no** necesita cambios: `ANY /api/{proxy+}` ya cubre `/api/users/**`, y `/usuarios`, `/pendiente`, `/mi-perfil` los sirve nginx por `ANY /{proxy+}` con `try_files`.

- [ ] **Step 6: Prueba de extremo a extremo con Azure** (Diego, ventanas de incógnito)

1. **Primero** entrar con `dieg.otarola@duocuc.cl` (Administrador): al ser el primer admin, entra directo al panel y ve **Usuarios**.
2. Otra ventana de incógnito: **Productor Demo** → pantalla **"Tu cuenta espera aprobación"** con su correo y fecha.
3. Ventana del admin: **Usuarios → Pendientes** → aparece Productor Demo con rol *Productor* → **Aprobar**.
4. Ventana del productor: **Volver a comprobar** → entra; en el menú aparece **Mi ficha**; guardar RUT `12345678-9`.
5. Repetir con **Jefe de Acopio**, **Auditor Demo** y **Admin AgroTrack** (este último nace pendiente: no es el primer admin).
6. Admin: **Rechazar** a una cuenta de prueba con motivo → esa persona, al recargar, ve "Tu solicitud fue rechazada" y el motivo (hasta 60 s de caché).
7. Admin intenta desactivarse a sí mismo: no aparece el botón.

Si algo devuelve 503 `USUARIOS_NO_DISPONIBLE`: `docker logs at-users` y `docker logs at-bff`.

- [ ] **Step 7: Apagar**

```powershell
cd C:\Users\deint\Desktop\AgroTrack\infra\aws; .\apagar.ps1
```

---

## Autorrevisión contra el spec

| Requisito del spec | Tarea |
|---|---|
| Microservicio `ms-agrotrack-users`, base `agro_users`, puerto 8089, resource server | 1 |
| Tablas `USUARIO` y `PERFIL_PRODUCTOR` con sus columnas y unicidad de `AZURE_OID` y `RUT` | 1, 3 |
| Máquina de estados pura con transiciones y motivo obligatorio | 2 |
| Regla del primer admin (única, auditable con `sistema`) | 2, 3 (incluida la concurrencia) |
| Un admin no se desactiva a sí mismo → 409 | 2, 5, 11 (botón oculto) |
| `sincronizar` idempotente, solo mueve `ULTIMO_INGRESO` | 3 |
| Sin rol admin nunca se auto-aprueba | 2, 3 |
| RUT duplicado se rechaza | 1, 4, 5 |
| Endpoints `sincronizar`, listar con filtro, detalle, estado, `me`, perfil (admin o el propio CLIENTE) | 4, 5 |
| `/api/me` del BFF sincroniza y devuelve estado | 7 |
| Filtro del BFF: 403 `CUENTA_PENDIENTE/RECHAZADA/INACTIVA`, `/api/me` exento | 7 |
| Caché 60 s; no sirve estado viejo; users caído → 503 | 6, 7 |
| Pantalla `/usuarios` con filtros, acciones por estado, motivo y ficha | 11 |
| Pantalla `/pendiente` con mensaje, correo y fecha, sin menú | 10 |
| Pantalla `/mi-perfil` para CLIENTE | 12 |
| `estadoGuard` → `/pendiente` | 9, 10 |
| Tests BFF: pendiente 403 en deliveries y 200 en me; activo pasa; 503; caché | 6, 7 |
| Tests frontend: guarda y acciones por estado | 9, 11 |
| Compose, `.env`, scripts y docs | 8 |
| Roles siguen del token; cambio de rol vía Graph fuera de alcance | Global Constraints (no hay tarea, a propósito) |

Decisiones tomadas al planificar, que el spec no fijaba:

- **Advisory lock** (`pg_advisory_xact_lock`) para que el primer admin sea único también con altas simultáneas; el spec pedía que "solo pueda ocurrir una vez" y sin bloqueo dos admins a la vez lo romperían.
- **`POST /api/users/sincronizar` no se expone por el BFF**: solo lo usa `/api/me` desde dentro.
- **`GET /api/users/me` va por el filtro**: un pendiente no lo necesita, porque `/pendiente` se alimenta de `/api/me`, que ya trae estado, motivo y fecha de solicitud.
- **El filtro va después de `AuthorizationFilter`**: un rol sin permiso recibe el 403 de la matriz sin gastar una llamada a `users`.
- **El smoke usa oids nuevos por corrida** y un único atajo SQL para activar `admin-smoke` si la base local ya tenía otro admin.
