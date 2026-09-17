package cl.agrotrack.users.infraestructura.persistencia;

import cl.agrotrack.users.dominio.EstadoUsuario;
import cl.agrotrack.users.dominio.MaquinaEstadosUsuario;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "usuario")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Usuario {

    /** Quien firma la aprobacion del primer admin. */
    public static final String APROBADOR_SISTEMA = "sistema";

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_usuario")
    @SequenceGenerator(name = "seq_usuario", sequenceName = "seq_usuario", allocationSize = 1)
    private Long id;

    @Column(name = "azure_oid", nullable = false, length = 50)
    private String azureOid;

    @Column(length = 200)
    private String email;

    @Column(length = 200)
    private String nombre;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstadoUsuario estado;

    @Column(name = "rol_ultimo_token", length = 20)
    private String rolUltimoToken;

    @Column(length = 500)
    private String motivo;

    @Column(name = "primer_ingreso", nullable = false)
    private Instant primerIngreso;

    @Column(name = "ultimo_ingreso", nullable = false)
    private Instant ultimoIngreso;

    @Column(name = "aprobado_por", length = 50)
    private String aprobadoPor;

    @Column(name = "aprobado_en")
    private Instant aprobadoEn;

    /** Dos admins aprobando y rechazando a la vez: gana uno, el otro recibe 409. */
    @Version
    private Long version;

    public static Usuario registrar(String azureOid, String email, String nombre, String rol,
                                    EstadoUsuario estadoInicial, Instant ahora) {
        Usuario u = new Usuario();
        u.azureOid = azureOid;
        u.email = email;
        u.nombre = nombre;
        u.rolUltimoToken = rol;
        u.estado = estadoInicial;
        u.primerIngreso = ahora;
        u.ultimoIngreso = ahora;
        if (estadoInicial == EstadoUsuario.ACTIVO) {
            u.aprobadoPor = APROBADOR_SISTEMA;
            u.aprobadoEn = ahora;
        }
        return u;
    }

    /** Cada ingreso refresca lo que viene del token. El estado no se toca. */
    public void registrarIngreso(String email, String nombre, String rol, Instant ahora) {
        this.email = email;
        this.nombre = nombre;
        this.rolUltimoToken = rol;
        this.ultimoIngreso = ahora;
    }

    public void cambiarEstado(EstadoUsuario destino, String motivo, String adminOid, Instant ahora) {
        MaquinaEstadosUsuario.validarCambio(estado, destino, motivo, azureOid.equals(adminOid));
        this.estado = destino;
        if (destino == EstadoUsuario.ACTIVO) {
            this.aprobadoPor = adminOid;
            this.aprobadoEn = ahora;
            this.motivo = null;
        } else {
            this.motivo = motivo.trim();
        }
    }
}
