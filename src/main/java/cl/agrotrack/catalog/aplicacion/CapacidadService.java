package cl.agrotrack.catalog.aplicacion;

import cl.agrotrack.catalog.aplicacion.Dtos.CapacidadRequest;
import cl.agrotrack.catalog.aplicacion.Dtos.CapacidadResponse;
import cl.agrotrack.catalog.infraestructura.persistencia.Bodega;
import cl.agrotrack.catalog.infraestructura.persistencia.BodegaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Consumer;

/**
 * Reserva y liberacion de capacidad.
 *
 * <p>Estrategia: la fila de la bodega se lee con {@code SELECT ... FOR UPDATE}
 * ({@link BodegaRepository#findParaActualizar}), asi que las reservas sobre
 * la misma bodega se serializan en la base y cada una ve la capacidad real.
 * El reintento ante {@link OptimisticLockingFailureException} queda como red
 * de seguridad para el caso raro en que otra operacion (un PUT del admin
 * redimensionando) toque la fila entre medio.
 *
 * <p>Por que no solo optimista: el test de concurrencia con 20 operadores
 * lo demostro — con contencion real los reintentos se agotan en cascada.
 */
@Service
public class CapacidadService {

    private static final Logger log = LoggerFactory.getLogger(CapacidadService.class);
    private static final int MAX_INTENTOS = 3;

    private final BodegaRepository bodegas;
    private final TransactionTemplate tx;

    public CapacidadService(BodegaRepository bodegas, TransactionTemplate tx) {
        this.bodegas = bodegas;
        this.tx = tx;
    }

    public CapacidadResponse reservar(Long bodegaId, CapacidadRequest req) {
        log.info("Reservando {} en bodega {} para entrega {}", req.cantidad(), bodegaId, req.entregaCodigo());
        return conBloqueo(bodegaId, b -> b.reservar(req.cantidad()));
    }

    public CapacidadResponse liberar(Long bodegaId, CapacidadRequest req) {
        log.info("Liberando {} en bodega {} por entrega {}", req.cantidad(), bodegaId, req.entregaCodigo());
        return conBloqueo(bodegaId, b -> b.liberar(req.cantidad()));
    }

    private CapacidadResponse conBloqueo(Long bodegaId, Consumer<Bodega> operacion) {
        OptimisticLockingFailureException ultima = null;
        for (int intento = 1; intento <= MAX_INTENTOS; intento++) {
            try {
                return tx.execute(status -> {
                    Bodega b = bodegas.findParaActualizar(bodegaId)
                            .orElseThrow(() -> new RecursoNoEncontradoException("Bodega", bodegaId));
                    operacion.accept(b);
                    bodegas.saveAndFlush(b);
                    return CapacidadResponse.de(b);
                });
            } catch (OptimisticLockingFailureException e) {
                ultima = e;
                log.debug("Colision optimista en bodega {} (intento {}/{})", bodegaId, intento, MAX_INTENTOS);
            }
        }
        throw new ConflictoConcurrenteException(bodegaId, ultima);
    }

    /** Colisiones repetidas incluso con el bloqueo: se devuelve 409 y que el cliente reintente. */
    public static class ConflictoConcurrenteException extends RuntimeException {
        public ConflictoConcurrenteException(Long bodegaId, Throwable causa) {
            super("La bodega " + bodegaId + " esta siendo modificada concurrentemente; reintente", causa);
        }
    }
}
