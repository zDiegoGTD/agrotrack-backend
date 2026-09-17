package cl.agrotrack.users.aplicacion;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/** Lo que este servicio necesita del token de Azure AD (o de mint.mjs en local). */
public record IdentidadToken(String oid, String email, String nombre, List<String> roles) {

    /** Si el token trae varios roles se muestra el mas privilegiado, igual que en deliveries. */
    private static final List<String> PRIORIDAD = List.of("ADMIN", "OPERADOR", "CLIENTE", "AUDITOR");

    public IdentidadToken {
        roles = roles == null ? List.of() : roles.stream().map(String::toUpperCase).toList();
    }

    public static IdentidadToken desde(Jwt jwt) {
        String oid = jwt.getClaimAsString("oid");
        String email = jwt.getClaimAsString("preferred_username");
        return new IdentidadToken(
                oid != null ? oid : jwt.getSubject(),
                email != null ? email : jwt.getClaimAsString("email"),
                jwt.getClaimAsString("name"),
                jwt.getClaimAsStringList("roles"));
    }

    public boolean esAdmin() {
        return roles.contains("ADMIN");
    }

    public String rolPrincipal() {
        return PRIORIDAD.stream().filter(roles::contains).findFirst().orElse(null);
    }
}
