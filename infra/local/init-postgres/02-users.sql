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
