# Claves JWT de desarrollo local

**Solo para el perfil `local`.** Estas claves firman tokens que unicamente
aceptan los servicios corriendo en tu PC con `spring.profiles.active=local`.
En AWS los tokens los firma Azure AD y estas claves no participan.

**La clave privada no se versiona** (`local-private.pem` esta en `infra/.gitignore`).
Solo la publica va al repo, copiada en cada servicio. En un clon nuevo, una vez:

```bash
node generar-claves.mjs           # crea el par y copia la publica a los servicios
```

y recompila los servicios. `node generar-claves.mjs --rotar` reemplaza el par
actual (asi se roto el 2026-10-19, cuando la privada dejo de versionarse: la que
quedo en el historial de git ya no es aceptada por ningun servicio).

```bash
node mint.mjs OPERADOR              # token de jefe de acopio (8 h)
node mint.mjs CLIENTE productor-01  # token de un productor concreto
node mint.mjs ADMIN,AUDITOR         # varios roles
```

La clave publica esta copiada en `src/main/resources/jwt/local-public.pem`
de cada servicio; `generar-claves.mjs` la copia sola al generar o rotar el par.
