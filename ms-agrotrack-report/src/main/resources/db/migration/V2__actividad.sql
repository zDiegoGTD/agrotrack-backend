-- Lo que report lee de audit.timeline (el segundo topico Kafka).

-- Eventos por rol del actor y hora: quien mueve el acopio.
CREATE TABLE actividad_rol (
    hora      TIMESTAMPTZ  NOT NULL,   -- truncada a la hora, UTC
    rol       VARCHAR(20)  NOT NULL,   -- SISTEMA si el evento no trae actor
    eventos   INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT pk_actividad_rol PRIMARY KEY (hora, rol)
);

-- Ultimo estado de cada entrega segun auditoria: un resumen que llega tarde
-- con fecha anterior no lo retrocede.
CREATE TABLE ultimo_estado (
    entrega_codigo  VARCHAR(30)  NOT NULL,
    estado          VARCHAR(20),
    ultimo_evento   VARCHAR(40)  NOT NULL,
    ocurrido_en     TIMESTAMPTZ  NOT NULL,
    actualizado_en  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_ultimo_estado PRIMARY KEY (entrega_codigo)
);

-- Los resumenes se deduplican con "timeline:<eventId>" para no chocar con el
-- eventId del evento original que ya registraron los KPI.
ALTER TABLE processed_events ALTER COLUMN event_id TYPE VARCHAR(60);
