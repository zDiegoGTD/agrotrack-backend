# Crear los 9 repositorios en GitHub

**No ejecutado todavía.** Publicar repos es una acción que sale de este
equipo, así que espera tu visto bueno explícito.

## Antes de subir

1. **Registra `dieg.otarola@duocuc.cl` en tu cuenta de GitHub**
   ([github.com/settings/emails](https://github.com/settings/emails)).
   Los commits ya están firmados con ese correo; si no está en la cuenta,
   aparecen sin autor reconocido y no cuentan como contribución tuya.
2. **Autentica el CLI** (abre el navegador, tienes que hacerlo tú):

   ```bash
   gh auth login
   ```

## Crear y subir los nueve

Con `gh` autenticado, desde `C:\Users\deint\Desktop\AgroTrack`:

```bash
for d in frontend-agrotrack ms-agrotrack-bff ms-agrotrack-deliveries \
         ms-agrotrack-catalog ms-agrotrack-notify ms-agrotrack-report \
         ms-agrotrack-audit infra docs; do
  cd "$d"
  gh repo create "zDiegoGTD/$d" --private --source=. --remote=origin --push
  cd ..
done
```

`--private` a propósito: se puede abrir después, pero un repo académico
público desde el día uno invita a que lo copien. Cámbialo a `--public` si la
pauta exige que el profesor lo vea sin invitación.

## Alternativa: un solo repo con nueve carpetas

Si la pauta no exige repos separados, un monorepo es bastante más liviano de
manejar para una persona sola: un `git clone`, un historial, una rama por
feature. El enunciado *sugiere* nueve repos, no los impone.

Decidir esto **antes** de crear los remotos: cambiar después significa
reescribir historial ya publicado.
