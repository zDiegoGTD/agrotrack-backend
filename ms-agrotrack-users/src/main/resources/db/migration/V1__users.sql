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
