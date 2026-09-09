# Decisiones de arquitectura

Cada decisión dice qué se decidió, por qué, y qué se rompería si la pauta del
martes dice lo contrario.

---

## D1 — PostgreSQL, una instancia, una base por servicio

**Enunciado:** `deliveries`, `catalog`, `audit` y `report` tienen "DB: Oracle".
**El profesor aclaró (2026-09-07) que el motor es flexible.**

**Decidido:** PostgreSQL 16, una sola instancia con una base y un rol por
servicio (`agro_deliveries`, `agro_catalog`, `agro_audit`, `agro_report`).
Cada servicio conoce solo sus credenciales.

**Por qué Postgres y no Oracle:** ~200 MB de RAM contra ~2 GB; arranca en
segundos, no en minutos; los tests de integración con Testcontainers pasan
de ~35 s a ~5 s; sin límites de licencia en AWS; `BOOLEAN`, `TIMESTAMPTZ` y
`JSONB` nativos. Se empezó con Oracle y se migró el mismo día, antes de
escribir `audit` y `report`: costó media hora.

**Por qué una instancia:** el aislamiento que importa ("cada servicio es
dueño de sus tablas y nadie más las toca") lo dan la base y el rol separados.
Cuatro instancias serían cuatro contenedores más sin ganancia real.

**Si la pauta exige Oracle:** el historial de git tiene la versión Oracle de
catalog y deliveries funcionando (commits anteriores al 2026-09-07 tarde).

---

## D2 — Spring Boot 3.5.16, no Boot 4

**Decidido:** los seis servicios usan Spring Boot **3.5.16** (la última 3.5).

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

**Si la pauta exige Boot 4:** cambiar `<version>` en los seis `pom.xml` y
revisar la clase de configuración de seguridad. Barato ahora, caro después.

## D3 — `EN_CLASIFICACION` sin tilde en el código

Ver `01-maquina-de-estados.md`. La tilde sólo en la UI.

---

## D4 — Desarrollo local, despliegue en AWS sólo al final

**Decidido:** todo se desarrolla contra `infra/local/compose.yml` (1 Oracle,
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

## D7 — H2 en los tests, Oracle en Docker

**Decidido:** los cuatro servicios con JPA usan H2 en memoria (modo de
compatibilidad Oracle) en `src/test/resources/application.properties`, y
excluyen las autoconfiguraciones de RabbitMQ y Kafka en los tests.

**Por qué:** sin esto el test `contextLoads` que genera Spring Initializr
falla de entrada — Spring no arranca sin una URL de datasource. Un proyecto
cuyo build está roto desde el commit inicial no sirve para nada.

**Límite conocido:** el modo Oracle de H2 es una aproximación, no Oracle. Los
tests que toquen SQL específico de Oracle (secuencias, `MERGE`, tipos de
fecha) van a mentir. Cuando Docker esté instalado, los tests de repositorio
pasan a **Testcontainers** contra la imagen real de Oracle; H2 se queda sólo
para los tests que no tocan la base.
