package cl.agrotrack.deliveries.config;

import cl.agrotrack.deliveries.dominio.Actor;
import cl.agrotrack.deliveries.dominio.Rol;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Construye el {@link Actor} de dominio a partir de la autenticacion de Spring. */
public final class Actores {

    /** Si el token trae varios roles, se actua con el mas privilegiado. */
    private static final List<Rol> PRIORIDAD = List.of(Rol.ADMIN, Rol.OPERADOR, Rol.CLIENTE, Rol.AUDITOR);

    private Actores() {
    }

    public static Actor desde(Authentication auth) {
        Set<String> autoridades = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        Rol rol = PRIORIDAD.stream()
                .filter(r -> autoridades.contains("ROLE_" + r.name()))
                .findFirst()
                .orElse(Rol.AUDITOR); // sin rol reconocido: el mas restrictivo

        String nombre = auth.getName();
        if (auth instanceof JwtAuthenticationToken jwt) {
            String n = jwt.getToken().getClaimAsString("name");
            if (n != null) {
                nombre = n;
            }
        }
        return new Actor(auth.getName(), nombre, rol);
    }
}
