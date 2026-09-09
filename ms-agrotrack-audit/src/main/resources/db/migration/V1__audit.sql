-- Base agro_audit (PostgreSQL). Ver docs/06-modelo-de-datos.md.

CREATE SEQUENCE seq_evento_timeline START 1 INCREMENT 1;

CREATE TABLE evento_timeline (
    id              BIGINT        NOT NULL,
    event_id        VARCHAR(36)   NOT NULL,
    entrega_codigo  VARCHAR(30)   NOT NULL,
    tipo            VARCHAR(50)   NOT NULL,
    actor_id        VARCHAR(50),
    actor_nombre    VARCHAR(120),
    actor_rol       VARCHAR(20),
    ocurrido_en     TIMESTAMPTZ   NOT NULL,   -- cuando paso en el dominio
    recibido_en     TIMESTAMPTZ   NOT NULL,   -- cuando lo vio auditoria
    trace_id        VARCHAR(64),
    correlation_id  VARCHAR(64),
    source          VARCHAR(60),
    payload         JSONB         NOT NULL,   -- el data completo del evento
    CONSTRAINT pk_evento_timeline PRIMARY KEY (id),
    CONSTRAINT uk_evento_timeline_event UNIQUE (event_id)
);

-- El timeline de una entrega en orden es LA consulta de este servicio.
CREATE INDEX ix_timeline_entrega ON evento_timeline (entrega_codigo, ocurrido_en);
-- Filtros de /api/audit/events: usuario, tipo, fechas.
CREATE INDEX ix_timeline_actor ON evento_timeline (actor_id, ocurrido_en);
CREATE INDEX ix_timeline_tipo  ON evento_timeline (tipo, ocurrido_en);

-- Un registro de auditoria que se puede editar no es auditoria: la base
-- rechaza cualquier UPDATE o DELETE, venga de donde venga.
CREATE FUNCTION timeline_inmutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'evento_timeline es de solo insercion (auditoria inmutable)';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_timeline_inmutable
    BEFORE UPDATE OR DELETE ON evento_timeline
    FOR EACH ROW EXECUTE FUNCTION timeline_inmutable();

-- Idempotencia del consumidor (docs/02-contrato-de-eventos.md)
CREATE TABLE processed_events (
    event_id      VARCHAR(36)   NOT NULL,
    processed_at  TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_processed_events PRIMARY KEY (event_id)
);
