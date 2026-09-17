package cl.agrotrack.bff.infraestructura.web;

import cl.agrotrack.bff.cuenta.CuentaUsuario;
import cl.agrotrack.bff.cuenta.EstadoCuentas;
import cl.agrotrack.bff.cuenta.UsuariosNoDisponibleException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Quien soy segun el token, y en que estado esta mi cuenta. Lo primero que
 * pide el frontend tras el login; de paso registra al usuario en users, asi
 * el alta ocurre sola y en un unico lugar.
 */
@RestController
public class MeController {

    public record Yo(String userId, String nombre, String email, List<String> roles,
                     Long usuarioId, String estado, String motivo, Instant primerIngreso) {
    }

    private final EstadoCuentas cuentas;

    public MeController(EstadoCuentas cuentas) {
        this.cuentas = cuentas;
    }

    @GetMapping("/api/me")
    public Yo me(JwtAuthenticationToken auth) {
        var jwt = auth.getToken();
        String oid = jwt.getClaimAsString("oid");
        String userId = oid != null ? oid : jwt.getSubject();
        String email = jwt.getClaimAsString("preferred_username");
        if (email == null) {
            email = jwt.getClaimAsString("email");
        }
        List<String> roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .sorted()
                .toList();
        CuentaUsuario cuenta = cuentas.sincronizar(userId, jwt.getTokenValue());
        return new Yo(userId, jwt.getClaimAsString("name"), email, roles, cuenta.id(), cuenta.estado(), cuenta.motivo(), cuenta.primerIngreso());
    }

    @ExceptionHandler(UsuariosNoDisponibleException.class)
    ProblemDetail usuariosNoDisponible(UsuariosNoDisponibleException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "No se pudo verificar tu cuenta; intenta en un momento");
        p.setTitle("Service Unavailable");
        p.setProperty("codigo", "USUARIOS_NO_DISPONIBLE");
        return p;
    }
}
