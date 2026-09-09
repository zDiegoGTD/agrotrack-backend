# Repositorios y entrega

## Los dos enlaces

| Repositorio | Qué contiene |
|---|---|
| **`github.com/zDiegoGTD/agrotrack-backend`** | Los 8 microservicios, `infra` y `docs` |
| **`github.com/zDiegoGTD/frontend-agrotrack`** | La aplicación Angular |

Son los dos que se entregan en AVA y por correo al docente. Ambos privados:
hay que **darle acceso al profesor y a la pareja** en *Settings →
Collaborators → Add people*.

## Por qué dos y no once

La pauta del EP1 dice:

> "el backend debe corresponder a **varios microservicios** construidos en Java
> con Spring Boot, el frontend debe ser un componente Angular. **Ambos tipos de
> componentes** deben ser entregados como enlaces a GitHub."

*Ambos tipos* son backend y frontend: dos enlaces. No pide un repositorio por
microservicio — eso lo **sugería** el enunciado del caso, sin imponerlo.

Al principio se crearon once repos siguiendo esa sugerencia. Se consolidaron
el 2026-09-08 por dos razones prácticas:

- **Entrega.** Once repos privados son once invitaciones que aceptar y once
  enlaces que abrir. Con dos, el evaluador clona y compila.
- **Trabajo diario.** Un `git clone` trae el sistema entero; antes había que
  sincronizar once repos a mano para que `docker compose` encontrara todo.

Los microservicios **siguen siendo independientes**: cada uno con su `pom.xml`,
su imagen Docker, su base de datos y su ciclo de despliegue. Comparten
repositorio, no código. Es un monorepo, no un monolito — la distinción importa
y conviene poder explicarla.

## Lo que NO se hizo, y por qué

Se evaluó poner cada componente en una **rama** distinta del mismo repo. Es un
error conocido:

- Las ramas guardan versiones de lo mismo en el tiempo, no cosas distintas.
- No se pueden tener dos componentes abiertos a la vez sin clonar dos veces.
- `docker compose up` necesita todo el código presente; una rama solo tiene un
  trozo.
- Un merge entre ramas intentaría fusionar código sin relación.

## Cómo se hizo la consolidación

Con `git subtree add`, que **conserva el historial completo** de cada repo
original: los 73 commits del backend incluyen los de cada microservicio con su
autor y fecha. No es una copia de archivos.

Las carpetas quedaron en el mismo sitio, así que ni los `compose.yml` ni los
scripts de despliegue necesitaron cambios.

Los ocho repos antiguos se eliminaron el 2026-09-08, tras verificar que los
241 archivos estaban en `agrotrack-backend`.
