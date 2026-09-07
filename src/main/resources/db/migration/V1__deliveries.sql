-- Base agro_deliveries (PostgreSQL). Ver docs/06-modelo-de-datos.md.

CREATE SEQUENCE seq_entrega START 1 INCREMENT 1;

-- Numera el codigo legible DEL-AAAA-NNNNNN, independiente de la PK.
CREATE SEQUENCE seq_entrega_codigo START 1 INCREMENT 1;

CREATE TABLE entrega (
    id               BIGINT          NOT NULL,
    codigo           VARCHAR(30)     NOT NULL,
    productor_id     VARCHAR(50)     NOT NULL,
    -- Sin FK: producto y bodega viven en otro servicio (agro_catalog).
    producto_id      BIGINT          NOT NULL,
    bodega_id        BIGINT          NOT NULL,
    cantidad         NUMERIC(12,2)   NOT NULL,
    peso_recibido    NUMERIC(12,2),
    estado           VARCHAR(20)     NOT NULL,
    motivo_rechazo   VARCHAR(500),
    fecha_registro   TIMESTAMPTZ     NOT NULL,
    fecha_recepcion  TIMESTAMPTZ,
    fecha_despacho   TIMESTAMPTZ,
    version          BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT pk_entrega          PRIMARY KEY (id),
    CONSTRAINT uk_entrega_codigo   UNIQUE (codigo),
    CONSTRAINT ck_entrega_estado   CHECK (estado IN (
        'REGISTRADA','RECIBIDA','EN_CLASIFICACION','EN_DESPACHO','DESPACHADA','RECHAZADA')),
    CONSTRAINT ck_entrega_cantidad CHECK (cantidad > 0)
);

-- GET /api/deliveries?status=&from=&to=
CREATE INDEX ix_entrega_estado_fecha ON entrega (estado, fecha_registro);
-- El productor viendo sus propias entregas
CREATE INDEX ix_entrega_productor ON entrega (productor_id, fecha_registro);

-- Idempotencia de consumidores (docs/02-contrato-de-eventos.md)
CREATE TABLE processed_events (
    event_id      VARCHAR(36)   NOT NULL,
    processed_at  TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_processed_events PRIMARY KEY (event_id)
);
