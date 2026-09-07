# Claves JWT de desarrollo local

**Solo para el perfil `local`.** Estas claves firman tokens que unicamente
aceptan los servicios corriendo en tu PC con `spring.profiles.active=local`.
En AWS los tokens los firma Azure AD y estas claves no participan.

Se versionan las dos (privada incluida) para que cualquier clon del repo
pueda emitir tokens sin pasos extra. Si alguna vez se filtran, no protegen
nada: no hay ningun entorno real que las acepte.

```bash
node mint.mjs OPERADOR              # token de jefe de acopio (8 h)
node mint.mjs CLIENTE productor-01  # token de un productor concreto
node mint.mjs ADMIN,AUDITOR         # varios roles
```

La clave publica esta copiada en `src/main/resources/jwt/local-public.pem`
de cada servicio. Si regeneras el par (`generar-claves.mjs`, borrando antes
los .pem), hay que volver a copiarla a todos.
