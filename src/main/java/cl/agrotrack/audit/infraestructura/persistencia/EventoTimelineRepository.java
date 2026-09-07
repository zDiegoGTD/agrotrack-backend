package cl.agrotrack.audit.infraestructura.persistencia;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public interface EventoTimelineRepository extends JpaRepository<EventoTimeline, Long>, JpaSpecificationExecutor<EventoTimeline> {

    List<EventoTimeline> findByEntregaCodigoOrderByOcurridoEnAscIdAsc(String entregaCodigo);

    /** Filtros de la pantalla de auditoria: usuario, fechas, tipo de evento. */
    static Specification<EventoTimeline> filtro(String actorId, Instant desde, Instant hasta, String tipo) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (actorId != null && !actorId.isBlank()) {
                p.add(cb.equal(root.get("actorId"), actorId));
            }
            if (desde != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("ocurridoEn"), desde));
            }
            if (hasta != null) {
                p.add(cb.lessThanOrEqualTo(root.get("ocurridoEn"), hasta));
            }
            if (tipo != null && !tipo.isBlank()) {
                p.add(cb.equal(root.get("tipo"), tipo));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
    }
}
