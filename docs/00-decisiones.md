# Decisiones de arquitectura

Cada decisión dice qué se decidió, por qué, y qué se rompería si la pauta del
martes dice lo contrario.

---

## D1 — PostgreSQL, una instancia, una base por servicio

**Enunciado:** `deliveries`, `catalog`, `audit` y `report` tienen "DB: Oracle".
**El profesor aclaró (2026-09-07) que el motor es flexible.**

**Decidido:** PostgreSQL 16, una sola instancia con una base y un rol por
servicio (`agro_deliveries`, `agro_catalog`, `agro_audit`, `agro_report`,
`agro_users`).
Cada servicio conoce solo sus credenciales.

**Por qué Postgres y no Oracle:** ~200 MB de RAM contra ~2 GB; arranca en
segundos, no en minutos; los tests de integración con Testcontainers pasan
de ~35 s a ~5 s; sin límites de licencia en AWS; `BOOLEAN`, `TIMESTAMPTZ` y
`JSONB` nativos. Se empezó con Oracle y se migró el mismo día, antes de
escribir `audit` y `report`: costó media hora.

**Por qué una instancia:** el aislamiento que importa ("cada servicio es
dueño de sus tablas y nadie más las toca") lo dan la base y el rol separados.
Cuatro instancias serían cuatro contenedores más sin ganancia real.

**Confirmado el 2026-09-08:** el docente ratificó que el motor es flexible —
al curso no se le entregaron cuentas de Oracle. PostgreSQL deja de ser una
desviación del enunciado y pasa a ser la elección aceptada.

**Reiterado el 2026-09-17:** al curso no se le dieron cuentas de bases de datos
en la nube (RDS u otro servicio administrado), y el docente indicó que sería
flexible en ese punto: PostgreSQL en contenedor sobre la EC2 de apps. No hay
migración a RDS pendiente.

**Si aun así se exigiera Oracle:** el historial de git tiene la versión Oracle
de catalog y deliveries funcionando (commits anteriores al 2026-09-07 tarde).

---

## D2 — Spring Boot 3.5.16, no Boot 4

**Decidido:** los nueve servicios usan Spring Boot **3.5.16** (la última 3.5).

**Por qué:** Spring Initializr ya sólo ofrece Boot 4.x, que trae Spring
Security 7 y una configuración de `oauth2ResourceServer` distinta a la de
Security 6. Prácticamente todo el material de curso, tutorial y respuesta de
StackOverflow que vas a encontrar sobre validar un JWT de Azure AD en Spring
está escrito para Security 6. Pelear contra eso todo el semestre no aporta
nada a la nota.

**Nota:** el `pom.xml` que genera Initializr para Boot 4 sale con la versión
`4.0.8.RELEASE`, que **no existe en Maven Central** (el artefacto real es
`4.0.8`, sin sufijo). El build falla de entrada. Se detectó al compilar, no
al suponer.

**Si la pauta exige Boot 4:** cambiar `<version>` en los nueve `pom.xml` y
revisar la clase de configuración de seguridad. Barato ahora, caro después.

## D3 — `EN_CLASIFICACION` sin tilde en el código

Ver `01-maquina-de-estados.md`. La tilde sólo en la UI.

---

## D4 — Desarrollo local, despliegue en AWS sólo al final

**Decidido:** todo se desarrolla contra `infra/local/compose.yml` (1 PostgreSQL,
1 Rabbit, 1 Kafka en KRaft). La topología real del enunciado (2 nodos Rabbit,
3 ZK + 3 brokers) se levanta en AWS.

**Por qué:** la topología completa son ~14 contenedores; no caben en 16 GB.
Y con AWS Academy, dejar instancias corriendo mientras se programa quema el
crédito sin dar nada a cambio.

**Excepción:** conviene hacer **un** despliegue de prueba temprano, apenas
haya un servicio que responda, para descubrir los problemas de red y de
security groups cuando todavía hay tiempo de resolverlos.

---

## D5 — Kafka primero, RabbitMQ después

Al ejecutar una transición, `deliveries` publica el hecho en Kafka y luego los
comandos en RabbitMQ. Ver `02-contrato-de-eventos.md`.

---

## D6 — La máquina de estados es una clase pura, sin Spring

Sin anotaciones, sin JPA, sin red. Recibe (estado actual, destino, rol) y
devuelve la validez y los efectos. Se prueba entera con tests unitarios en
milisegundos, sin levantar infraestructura.

**Por qué importa:** es la parte del sistema con más reglas y más casos borde,
y es justo la que la pauta va a revisar. Si está enredada con JPA y Rabbit,
probarla exige levantar todo el stack, y en la práctica no se prueba.

---

## D7 — Tests de persistencia contra PostgreSQL real (Testcontainers)

**Decidido:** los servicios con base de datos prueban sus repositorios y
migraciones contra un **PostgreSQL 16 real en Docker** (Testcontainers), la
misma imagen que en local y en AWS. Los tests de dominio y de controladores no
levantan base.

**Historia:** al principio se usó H2 en modo compatibilidad Oracle para que el
`contextLoads` de Initializr compilara. Se reemplazó en cuanto hubo Docker: H2
aproxima el SQL y deja pasar errores que la base real no deja (tipos de fecha,
secuencias, `CHECK`, bloqueos). Ejemplo concreto: el redondeo a microsegundos
de las fechas de `ms-agrotrack-users` solo lo detectó PostgreSQL real.

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

---

## D9 — El API Gateway como única puerta al BFF

**Decidido:** el API Gateway agrega la cabecera `X-Origen-Gateway` con un
secreto a cada llamada al BFF, y el BFF (`FiltroOrigenGateway`) niega con
`403 ORIGEN_NO_PERMITIDO` lo que no la traiga.

**Por qué:** el HTTP API de AWS solo llega a destinos públicos, así que el
puerto del BFF tiene que estar abierto en la EC2. Sin la cabecera se podría
llamar directo a la máquina y saltarse las validaciones del Gateway. El token
se seguiría validando en el BFF, pero la pauta pone al Gateway como entrada.

**Operación:** el secreto vive en `infra/.env.aws` (`GATEWAY_SECRETO`, fuera del
repo); `infra/aws/gateway.ps1` lo genera si falta y lo configura en la
integración. Al cambiarlo hay que redesplegar el stack `apps`.

---

## D10 — Rechazos de seguridad que dicen por qué

**Decidido:** los 401 y 403 del BFF llevan la cabecera `WWW-Authenticate`
(RFC 6750) y un cuerpo `problem+json` (RFC 7807) con un `codigo`:
`TOKEN_AUSENTE`, `TOKEN_VENCIDO`, `TOKEN_AUN_NO_VALIDO`, `AUDIENCIA_INVALIDA`,
`EMISOR_INVALIDO`, `FIRMA_INVALIDA`, `SCOPE_INSUFICIENTE`, `TOKEN_INVALIDO`,
`ROL_INSUFICIENTE`.

**Por qué:** la pauta pide "códigos de error adecuados". Spring por defecto
responde el código sin cuerpo, y el frontend no puede distinguir un token
vencido de un rol insuficiente o de un problema de configuración.

**Cómo se prueba:** `ValidacionJwtTest`, con tokens firmados de verdad y la
misma configuración del perfil `aws` (issuer, audiencias y scope requerido).
