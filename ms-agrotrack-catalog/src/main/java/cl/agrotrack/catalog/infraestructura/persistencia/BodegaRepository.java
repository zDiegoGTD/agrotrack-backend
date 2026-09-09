package cl.agrotrack.catalog.infraestructura.persistencia;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BodegaRepository extends JpaRepository<Bodega, Long> {

    List<Bodega> findAllByOrderByNombre();

    /**
     * SELECT ... FOR UPDATE. Para la fila de una bodega en plena recepcion
     * de lotes la contencion es la norma, y ahi el bloqueo optimista solo
     * genera reintentos en cascada. Se serializan las reservas en la base y
     * cada una ve la capacidad real. Solo se usa para reservar/liberar; el
     * resto de la app sigue con el @Version.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Bodega b where b.id = :id")
    Optional<Bodega> findParaActualizar(@Param("id") Long id);
}
