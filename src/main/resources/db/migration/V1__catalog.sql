-- Base agro_catalog (PostgreSQL). Ver docs/06-modelo-de-datos.md.

CREATE SEQUENCE seq_producto START 1 INCREMENT 1;

CREATE TABLE producto (
    id              BIGINT          NOT NULL,
    codigo          VARCHAR(30)     NOT NULL,
    nombre          VARCHAR(120)    NOT NULL,
    unidad_medida   VARCHAR(10)     NOT NULL,
    tarifa          NUMERIC(12,2)   NOT NULL,
    activo          BOOLEAN         NOT NULL DEFAULT TRUE,
    CONSTRAINT pk_producto        PRIMARY KEY (id),
    CONSTRAINT uk_producto_codigo UNIQUE (codigo),
    CONSTRAINT ck_producto_tarifa CHECK (tarifa >= 0)
);

CREATE SEQUENCE seq_bodega START 1 INCREMENT 1;

CREATE TABLE bodega (
    id                      BIGINT          NOT NULL,
    nombre                  VARCHAR(120)    NOT NULL,
    ubicacion               VARCHAR(200),
    capacidad_total         NUMERIC(12,2)   NOT NULL,
    capacidad_disponible    NUMERIC(12,2)   NOT NULL,
    version                 BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT pk_bodega PRIMARY KEY (id),
    -- Ultima linea de defensa: aunque el codigo tenga un bug, la base no
    -- acepta una bodega con capacidad negativa ni por encima del total.
    CONSTRAINT ck_bodega_capacidad CHECK (
        capacidad_disponible >= 0 AND capacidad_disponible <= capacidad_total
    )
);
