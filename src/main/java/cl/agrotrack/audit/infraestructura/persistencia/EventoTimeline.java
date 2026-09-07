package cl.agrotrack.audit.infraestructura.persistencia;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/** Una fila por evento recibido. Solo se inserta; la base impide modificarla. */
@Entity
@Table(name = "evento_timeline")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventoTimeline {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_evento_timeline")
    @SequenceGenerator(name = "seq_evento_timeline", sequenceName = "seq_evento_timeline", allocationSize = 1)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 36, unique = true)
    private String eventId;

    @Column(name = "entrega_codigo", nullable = false, length = 30)
    private String entregaCodigo;

    @Column(nullable = false, length = 50)
    private String tipo;

    @Column(name = "actor_id", length = 50)
    private String actorId;

    @Column(name = "actor_nombre", length = 120)
    private String actorNombre;

    @Column(name = "actor_rol", length = 20)
    private String actorRol;

    @Column(name = "ocurrido_en", nullable = false)
    private Instant ocurridoEn;

    @Column(name = "recibido_en", nullable = false)
    private Instant recibidoEn;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(length = 60)
    private String source;

    /** El data completo del evento, como JSONB: lo que hoy no se consulta igual queda. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> payload;

    public EventoTimeline(String eventId, String entregaCodigo, String tipo,
                          String actorId, String actorNombre, String actorRol,
                          Instant ocurridoEn, Instant recibidoEn,
                          String traceId, String correlationId, String source,
                          Map<String, Object> payload) {
        this.eventId = eventId;
        this.entregaCodigo = entregaCodigo;
        this.tipo = tipo;
        this.actorId = actorId;
        this.actorNombre = actorNombre;
        this.actorRol = actorRol;
        this.ocurridoEn = ocurridoEn;
        this.recibidoEn = recibidoEn;
        this.traceId = traceId;
        this.correlationId = correlationId;
        this.source = source;
        this.payload = payload == null ? Map.of() : payload;
    }
}
