package cl.agrotrack.bff.cuenta;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Segunda capa: tras la matriz de roles, comprueba que la cuenta este
 * ACTIVA en ms-agrotrack-users. Asi un despedido deja de entrar aunque su
 * cuenta de Microsoft siga viva.
 *
 * <p>No es un @Component a proposito: Spring Boot registraria cualquier
 * Filter bean en el contenedor, delante de Spring Security, donde todavia
 * no hay usuario autenticado. Se instancia en SecurityConfig.
 */
public class FiltroCuentaActiva extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FiltroCuentaActiva.class);

    private record Bloqueo(String codigo, String mensaje) {
    }

    private static final Map<String, Bloqueo> BLOQUEOS = Map.of(
            "PENDIENTE", new Bloqueo("CUENTA_PENDIENTE", "Tu cuenta espera aprobación"),
            "RECHAZADO", new Bloqueo("CUENTA_RECHAZADA", "Tu solicitud fue rechazada"),
            "INACTIVO", new Bloqueo("CUENTA_INACTIVA", "Tu cuenta fue desactivada"));

    private final EstadoCuentas cuentas;
    private final ObjectMapper json;

    public FiltroCuentaActiva(EstadoCuentas cuentas, ObjectMapper json) {
        this.cuentas = cuentas;
        this.json = json;
    }

    /** /api/me queda fuera: es por donde se registra y por donde /pendiente sabe que mostrar. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String ruta = request.getRequestURI();
        return !ruta.startsWith("/api/") || ruta.equals("/api/me") || HttpMethod.OPTIONS.matches(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken token)) {
            chain.doFilter(request, response);
            return;
        }
        String oid = token.getToken().getClaimAsString("oid");
        if (oid == null) {
            oid = token.getToken().getSubject();
        }

        CuentaUsuario cuenta;
        try {
            cuenta = cuentas.consultar(oid, token.getToken().getTokenValue());
        } catch (UsuariosNoDisponibleException e) {
            log.warn("No se pudo verificar la cuenta {}: {}", oid, e.getMessage());
            escribir(response, HttpStatus.SERVICE_UNAVAILABLE, "USUARIOS_NO_DISPONIBLE",
                    "No se pudo verificar tu cuenta; intenta en un momento", null);
            return;
        }

        if (cuenta.activa()) {
            chain.doFilter(request, response);
            return;
        }
        Bloqueo b = BLOQUEOS.getOrDefault(cuenta.estado(), new Bloqueo("CUENTA_NO_ACTIVA", "Tu cuenta no está activa"));
        escribir(response, HttpStatus.FORBIDDEN, b.codigo(), b.mensaje(), cuenta.estado());
    }

    private void escribir(HttpServletResponse response, HttpStatus status, String codigo, String detalle, String estado)
            throws IOException {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detalle);
        p.setTitle(status.getReasonPhrase());
        p.setProperty("codigo", codigo);
        if (estado != null) {
            p.setProperty("estado", estado);
        }
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), p);
    }
}
