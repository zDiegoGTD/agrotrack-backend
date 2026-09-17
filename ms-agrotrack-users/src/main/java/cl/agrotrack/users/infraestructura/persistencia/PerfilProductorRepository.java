package cl.agrotrack.users.infraestructura.persistencia;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PerfilProductorRepository extends JpaRepository<PerfilProductor, Long> {

    Optional<PerfilProductor> findByRut(String rut);
}
