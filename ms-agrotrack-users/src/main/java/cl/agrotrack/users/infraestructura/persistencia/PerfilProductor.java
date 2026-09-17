package cl.agrotrack.users.infraestructura.persistencia;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "perfil_productor")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PerfilProductor {

    /** Misma clave que el usuario: una ficha por persona. */
    @Id
    @Column(name = "usuario_id")
    private Long usuarioId;

    @Column(nullable = false, length = 15)
    private String rut;

    @Column(name = "razon_social", nullable = false, length = 200)
    private String razonSocial;

    @Column(length = 30)
    private String telefono;

    @Column(length = 300)
    private String direccion;

    @Column(name = "bodega_habitual_id")
    private Long bodegaHabitualId;

    public PerfilProductor(Long usuarioId) {
        this.usuarioId = usuarioId;
    }

    public void actualizar(String rut, String razonSocial, String telefono, String direccion, Long bodegaHabitualId) {
        this.rut = rut.trim().toUpperCase();
        this.razonSocial = razonSocial.trim();
        this.telefono = telefono;
        this.direccion = direccion;
        this.bodegaHabitualId = bodegaHabitualId;
    }
}
