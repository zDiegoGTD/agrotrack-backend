package cl.agrotrack.users.aplicacion;

import cl.agrotrack.users.dominio.EstadoUsuario;
import cl.agrotrack.users.infraestructura.persistencia.PerfilProductor;
import cl.agrotrack.users.infraestructura.persistencia.Usuario;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class Dtos {

    private Dtos() {
    }

    public record UsuarioResponse(
            Long id, String azureOid, String email, String nombre, EstadoUsuario estado,
            String rolUltimoToken, String motivo, Instant primerIngreso, Instant ultimoIngreso,
            String aprobadoPor, Instant aprobadoEn, PerfilResponse perfil) {

        public static UsuarioResponse de(Usuario u, PerfilProductor perfil) {
            return new UsuarioResponse(u.getId(), u.getAzureOid(), u.getEmail(), u.getNombre(), u.getEstado(),
                    u.getRolUltimoToken(), u.getMotivo(), u.getPrimerIngreso(), u.getUltimoIngreso(),
                    u.getAprobadoPor(), u.getAprobadoEn(), perfil == null ? null : PerfilResponse.de(perfil));
        }
    }

    public record PerfilResponse(String rut, String razonSocial, String telefono, String direccion, Long bodegaHabitualId) {

        public static PerfilResponse de(PerfilProductor p) {
            return new PerfilResponse(p.getRut(), p.getRazonSocial(), p.getTelefono(), p.getDireccion(), p.getBodegaHabitualId());
        }
    }

    public record CambioEstadoRequest(@NotNull EstadoUsuario estado, @Size(max = 500) String motivo) {
    }

    public record PerfilRequest(
            @NotBlank @Pattern(regexp = "^\\d{7,8}-[\\dkK]$", message = "RUT con guion, sin puntos: 12345678-9") String rut,
            @NotBlank @Size(max = 200) String razonSocial,
            @Size(max = 30) String telefono,
            @Size(max = 300) String direccion,
            Long bodegaHabitualId) {
    }
}
