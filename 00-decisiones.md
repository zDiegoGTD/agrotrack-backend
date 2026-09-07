# Decisiones de arquitectura

Cada decisión dice qué se decidió, por qué, y qué se rompería si la pauta del
martes dice lo contrario.

---

## D1 — Una instancia Oracle con cuatro esquemas, no cuatro instancias

**Enunciado:** `deliveries`, `catalog`, `audit` y `report` tienen "DB: Oracle".

**Decidido:** una sola instancia Oracle Free con un esquema por servicio
(`agro_deliveries`, `agro_catalog`, `agro_audit`, `agro_report`), sin acceso
cruzado entre esquemas.

**Por qué:** Oracle XE/Free pide ~2 GB de RAM por instancia. Cuatro instancias
son 8 GB solo en bases de datos — no caben en un PC de 16 GB ni en una EC2 de
capa gratuita. El aislamiento por esquema da la misma propiedad que importa
("cada servicio es dueño de sus tablas y nadie más las toca") a un costo
realista.

**Si la pauta exige instancias separadas:** cambia la URL de conexión de cada
servicio. Media hora, ningún cambio de código.

---

## D2 — Spring Boot 4.0.8 (LTS estable actual)

**Decidido:** los seis servicios se generaron con Spring Boot 4.0.8.

**Riesgo conocido:** el material del curso probablemente usa Spring Boot 3.x,
y Spring Security 7 (que viene con Boot 4) cambió la configuración de
`oauth2ResourceServer` respecto a Security 6. Si los ejemplos de la pauta son
de Boot 3, van a fallar copiados tal cual.

**Es la decisión #1 a confirmar el martes.** Bajar a 3.5.x en este momento es
cambiar una versión en cada `pom.xml`, porque todavía no hay una línea de
código escrita. Después de escribir la capa de seguridad, ya no.

---

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
