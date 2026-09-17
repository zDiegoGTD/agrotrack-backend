package cl.agrotrack.users.infraestructura.web;

import cl.agrotrack.users.aplicacion.Dtos.CambioEstadoRequest;
import cl.agrotrack.users.aplicacion.Dtos.PerfilRequest;
import cl.agrotrack.users.aplicacion.Dtos.PerfilResponse;
import cl.agrotrack.users.aplicacion.Dtos.UsuarioResponse;
import cl.agrotrack.users.aplicacion.IdentidadToken;
import cl.agrotrack.users.aplicacion.UsuarioService;
import cl.agrotrack.users.dominio.EstadoUsuario;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Cuentas de AgroTrack. El rol sigue saliendo del token; aqui solo se
 * decide si la cuenta esta aprobada.
 */
@RestController
@RequestMapping("/api/users")
public class UsuarioController {

    private final UsuarioService servicio;

    public UsuarioController(UsuarioService servicio) {
        this.servicio = servicio;
    }

    /** Lo invoca el BFF desde /api/me en cada ingreso. */
    @PostMapping("/sincronizar")
    @PreAuthorize("isAuthenticated()")
    public UsuarioResponse sincronizar(JwtAuthenticationToken auth) {
        return servicio.sincronizar(IdentidadToken.desde(auth.getToken()));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<UsuarioResponse> listar(@RequestParam(required = false) EstadoUsuario estado) {
        return servicio.listar(estado);
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public UsuarioResponse me(JwtAuthenticationToken auth) {
        return servicio.me(IdentidadToken.desde(auth.getToken()).oid());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public UsuarioResponse obtener(@PathVariable Long id) {
        return servicio.obtener(id);
    }

    @PutMapping("/{id}/estado")
    @PreAuthorize("hasRole('ADMIN')")
    public UsuarioResponse cambiarEstado(@PathVariable Long id, @Valid @RequestBody CambioEstadoRequest req,
                                         JwtAuthenticationToken auth) {
        return servicio.cambiarEstado(id, req, IdentidadToken.desde(auth.getToken()).oid());
    }

    /** El servicio comprueba que un CLIENTE solo toque su propia ficha. */
    @PutMapping("/{id}/perfil")
    @PreAuthorize("hasAnyRole('ADMIN','CLIENTE')")
    public PerfilResponse actualizarPerfil(@PathVariable Long id, @Valid @RequestBody PerfilRequest req,
                                           JwtAuthenticationToken auth) {
        return servicio.actualizarPerfil(id, req, IdentidadToken.desde(auth.getToken()));
    }
}
