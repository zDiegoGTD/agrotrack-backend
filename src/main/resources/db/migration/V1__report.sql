-- Base agro_report (PostgreSQL). Agregaciones precalculadas desde Kafka.
-- Ver docs/06-modelo-de-datos.md: report NO consulta las tablas del core;
-- suma a medida que llegan los eventos y sirve el panel con SELECTs triviales.

-- Entregas por hora y bodega: los contadores del panel.
CREATE TABLE kpi_entregas_hora (
    hora          TIMESTAMPTZ  NOT NULL,   -- truncada a la hora, UTC
    bodega_id     BIGINT       NOT NULL,
    registradas   INTEGER      NOT NULL DEFAULT 0,
    recibidas     INTEGER      NOT NULL DEFAULT 0,
    despachadas   INTEGER      NOT NULL DEFAULT 0,
    rechazadas    INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT pk_kpi_entregas_hora PRIMARY KEY (hora, bodega_id)
);

-- Una fila por entrega con su ciclo de vida resumido: de aqui salen el
-- tiempo de ciclo, los estados activos y los productos mas recibidos.
CREATE TABLE ciclo_entrega (
    entrega_codigo   VARCHAR(30)    NOT NULL,
    bodega_id        BIGINT,
    producto_id      BIGINT,
    productor_id     VARCHAR(50),
    peso             NUMERIC(12,2),
    estado           VARCHAR(20)    NOT NULL,
    fecha_registro   TIMESTAMPTZ,
    fecha_recepcion  TIMESTAMPTZ,
    fecha_cierre     TIMESTAMPTZ,            -- despachada o rechazada
    minutos_ciclo    INTEGER,                -- registro -> despacho
    actualizado_en   TIMESTAMPTZ    NOT NULL,
    CONSTRAINT pk_ciclo_entrega PRIMARY KEY (entrega_codigo)
);

CREATE INDEX ix_ciclo_estado    ON ciclo_entrega (estado);
CREATE INDEX ix_ciclo_recepcion ON ciclo_entrega (fecha_recepcion);
CREATE INDEX ix_ciclo_cierre    ON ciclo_entrega (fecha_cierre);

-- Idempotencia del consumidor (docs/02-contrato-de-eventos.md)
CREATE TABLE processed_events (
    event_id      VARCHAR(36)   NOT NULL,
    processed_at  TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_processed_events PRIMARY KEY (event_id)
);
