package cl.agrotrack.deliveries.infraestructura.persistencia;

import cl.agrotrack.deliveries.dominio.EstadoEntrega;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public interface EntregaRepository extends JpaRepository<Entrega, Long>, JpaSpecificationExecutor<Entrega> {

    Optional<Entrega> findByCodigo(String codigo);

    @Query(value = "SELECT SEQ_ENTREGA_CODIGO.NEXTVAL FROM dual", nativeQuery = true)
    long siguienteNumeroDeCodigo();

    /**
     * Filtro de GET /api/deliveries. Se arma con Specifications y no con un
     * JPQL de ":x is null or ..." porque Oracle no acepta bind de null sin
     * tipo en comparaciones de TIMESTAMP (ORA-00932).
     */
    static Specification<Entrega> filtro(EstadoEntrega estado, Instant desde, Instant hasta, String productorId) {
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> p = new ArrayList<>();
            if (estado != null) {
                p.add(cb.equal(root.get("estado"), estado));
            }
            if (desde != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("fechaRegistro"), desde));
            }
            if (hasta != null) {
                p.add(cb.lessThanOrEqualTo(root.get("fechaRegistro"), hasta));
            }
            if (productorId != null) {
                p.add(cb.equal(root.get("productorId"), productorId));
            }
            query.orderBy(cb.desc(root.get("fechaRegistro")));
            return cb.and(p.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }
}
