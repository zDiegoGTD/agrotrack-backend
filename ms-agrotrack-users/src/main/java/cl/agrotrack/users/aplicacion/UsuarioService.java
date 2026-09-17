package cl.agrotrack.users.aplicacion;

import cl.agrotrack.users.aplicacion.Dtos.CambioEstadoRequest;
import cl.agrotrack.users.aplicacion.Dtos.PerfilRequest;
import cl.agrotrack.users.aplicacion.Dtos.PerfilResponse;
import cl.agrotrack.users.aplicacion.Dtos.UsuarioResponse;
import cl.agrotrack.users.dominio.EstadoUsuario;
import cl.agrotrack.users.dominio.MaquinaEstadosUsuario;
import cl.agrotrack.users.infraestructura.persistencia.PerfilProductor;
import cl.agrotrack.users.infraestructura.persistencia.PerfilProductorRepository;
import cl.agrotrack.users.infraestructura.persistencia.Usuario;
import cl.agrotrack.users.infraestructura.persistencia.UsuarioRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
public class UsuarioService {

    /** Clave del advisory lock que serializa las altas. Arbitraria, pero fija. */
    static final long BLOQUEO_ALTAS = 7_001L;

    private final UsuarioRepository usuarios;
    private final PerfilProductorRepository perfiles;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public UsuarioService(UsuarioRepository usuarios, PerfilProductorRepository perfiles, JdbcTemplate jdbc, Clock clock) {
        this.usuarios = usuarios;
        this.perfiles = perfiles;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Alta o actualizacion desde los claims. Idempotente: llamarlo cien veces
     * deja el mismo registro y solo mueve ultimo_ingreso.
     */
    public UsuarioResponse sincronizar(IdentidadToken id) {
        Instant ahora = ahora();
        Optional<Usuario> existente = usuarios.findByAzureOid(id.oid());
        if (existente.isEmpty()) {
            // Sin este bloqueo, dos admins entrando a la vez con el sistema vacio
            // verian los dos "no hay activos" y quedarian los dos aprobados: la
            // excepcion del primer admin dejaria de ser unica. Se libera solo
            // al terminar la transaccion.
            jdbc.execute("SELECT pg_advisory_xact_lock(" + BLOQUEO_ALTAS + ")");
            existente = usuarios.findByAzureOid(id.oid());
        }
        Usuario u;
        if (existente.isPresent()) {
            u = existente.get();
            u.registrarIngreso(id.email(), id.nombre(), id.rolPrincipal(), ahora);
        } else {
            EstadoUsuario inicial = MaquinaEstadosUsuario.estadoInicial(id.esAdmin(), usuarios.existsByEstado(EstadoUsuario.ACTIVO));
            u = usuarios.save(Usuario.registrar(id.oid(), id.email(), id.nombre(), id.rolPrincipal(), inicial, ahora));
        }
        return UsuarioResponse.de(u, perfiles.findById(u.getId()).orElse(null));
    }

    @Transactional(readOnly = true)
    public List<UsuarioResponse> listar(EstadoUsuario estado) {
        List<Usuario> lista = estado == null
                ? usuarios.findAllByOrderByPrimerIngresoDesc()
                : usuarios.findByEstadoOrderByPrimerIngresoDesc(estado);
        // Una sola consulta para todas las fichas, no una por fila
        Map<Long, PerfilProductor> fichas = perfiles.findAllById(lista.stream().map(Usuario::getId).toList())
                .stream().collect(Collectors.toMap(PerfilProductor::getUsuarioId, Function.identity()));
        return lista.stream().map(u -> UsuarioResponse.de(u, fichas.get(u.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public UsuarioResponse obtener(Long id) {
        Usuario u = usuario(id);
        return UsuarioResponse.de(u, perfiles.findById(id).orElse(null));
    }

    @Transactional(readOnly = true)
    public UsuarioResponse me(String oid) {
        Usuario u = usuarios.findByAzureOid(oid).orElseThrow(() -> new RecursoNoEncontradoException("Usuario", oid));
        return UsuarioResponse.de(u, perfiles.findById(u.getId()).orElse(null));
    }

    public UsuarioResponse cambiarEstado(Long id, CambioEstadoRequest req, String adminOid) {
        Usuario u = usuario(id);
        u.cambiarEstado(req.estado(), req.motivo(), adminOid, ahora());
        return UsuarioResponse.de(u, perfiles.findById(id).orElse(null));
    }

    /** El admin edita cualquier ficha; el productor, solo la suya. */
    public PerfilResponse actualizarPerfil(Long id, PerfilRequest req, IdentidadToken quien) {
        Usuario u = usuario(id);
        if (!quien.esAdmin() && !u.getAzureOid().equals(quien.oid())) {
            throw new AccessDeniedException("Solo puedes editar tu propia ficha");
        }
        String rut = req.rut().trim().toUpperCase();
        perfiles.findByRut(rut)
                .filter(otra -> !otra.getUsuarioId().equals(id))
                .ifPresent(otra -> {
                    throw new RutDuplicadoException(rut);
                });
        PerfilProductor p = perfiles.findById(id).orElseGet(() -> new PerfilProductor(id));
        p.actualizar(rut, req.razonSocial(), req.telefono(), req.direccion(), req.bodegaHabitualId());
        return PerfilResponse.de(perfiles.save(p));
    }

    /**
     * PostgreSQL guarda microsegundos y el reloj de Java en Windows da
     * decimas de microsegundo: sin redondear, la respuesta del alta traeria
     * una hora distinta de la que queda guardada.
     */
    private Instant ahora() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private Usuario usuario(Long id) {
        return usuarios.findById(id).orElseThrow(() -> new RecursoNoEncontradoException("Usuario", id));
    }
}
