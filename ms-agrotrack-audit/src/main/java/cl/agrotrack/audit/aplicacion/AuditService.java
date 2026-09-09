package cl.agrotrack.audit.aplicacion;

import cl.agrotrack.audit.infraestructura.persistencia.EventoTimeline;
import cl.agrotrack.audit.infraestructura.persistencia.EventoTimelineRepository;
import cl.agrotrack.audit.infraestructura.persistencia.RegistroIdempotente;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Registra eventos en el timeline y los consulta. */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final int MAX_RESULTADOS = 500;

    private final EventoTimelineRepository timeline;
    private final RegistroIdempotente idempotencia;
    private final ObjectMapper json;
    private final Clock clock;

    public AuditService(EventoTimelineRepository timeline, RegistroIdempotente idempotencia,
                        ObjectMapper json, Clock clock) {
        this.timeline = timeline;
        this.idempotencia = idempotencia;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Procesa un evento crudo de deliveries.events.
     *
     * @return el evento registrado, o vacio si ya se habia procesado
     * @throws MensajeInvalidoException si el JSON no es un envelope valido
     */
    @Transactional
    public Optional<EventoTimeline> registrar(String cuerpo) {
        Envelope env = parsear(cuerpo);
        if (!idempotencia.marcarSiEsNuevo(env.eventId())) {
            log.info("[dup] {} {} ya registrado", env.type(), env.eventId());
            return Optional.empty();
        }
        EventoTimeline e = new EventoTimeline(
                env.eventId(), env.subject(), env.type(),
                env.actor() != null ? env.actor().userId() : null,
                env.actor() != null ? env.actor().nombre() : null,
                env.actor() != null ? env.actor().role() : null,
                env.occurredAt(), Instant.now(clock),
                env.traceId(), env.correlationId(), env.source(),
                env.data());
        e = timeline.save(e);
        log.info("[timeline] {} {} por {}", env.type(), env.subject(), e.getActorId());
        return Optional.of(e);
    }

    @Transactional(readOnly = true)
    public List<EventoTimeline> timelineDe(String entregaCodigo) {
        return timeline.findByEntregaCodigoOrderByOcurridoEnAscIdAsc(entregaCodigo);
    }

    @Transactional(readOnly = true)
    public List<EventoTimeline> buscar(String actorId, Instant desde, Instant hasta, String tipo, int limite) {
        int tam = Math.max(1, Math.min(limite, MAX_RESULTADOS));
        return timeline.findAll(
                EventoTimelineRepository.filtro(actorId, desde, hasta, tipo),
                PageRequest.of(0, tam, Sort.by(Sort.Direction.DESC, "ocurridoEn", "id"))).getContent();
    }

    Envelope parsear(String cuerpo) {
        Envelope env;
        try {
            env = json.readValue(cuerpo, Envelope.class);
        } catch (IOException e) {
            throw new MensajeInvalidoException("JSON invalido: " + resumen(cuerpo), e);
        }
        if (env.eventId() == null || env.eventId().isBlank()) {
            throw new MensajeInvalidoException("Evento sin eventId: " + resumen(cuerpo));
        }
        if (env.type() == null || env.subject() == null || env.occurredAt() == null) {
            throw new MensajeInvalidoException("Evento sin type/subject/occurredAt: " + resumen(cuerpo));
        }
        return env;
    }

    private static String resumen(String s) {
        return s == null ? "null" : s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
