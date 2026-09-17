package cl.agrotrack.users.infraestructura.persistencia;

import cl.agrotrack.users.dominio.EstadoUsuario;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    Optional<Usuario> findByAzureOid(String azureOid);

    boolean existsByEstado(EstadoUsuario estado);

    List<Usuario> findAllByOrderByPrimerIngresoDesc();

    List<Usuario> findByEstadoOrderByPrimerIngresoDesc(EstadoUsuario estado);
}
