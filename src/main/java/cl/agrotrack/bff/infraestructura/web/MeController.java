package cl.agrotrack.bff.infraestructura.web;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Quien soy segun el token: lo primero que pide el frontend tras el login. */
@RestController
public class MeController {

    public record Yo(String userId, String nombre, String email, List<String> roles) {
    }

    @GetMapping("/api/me")
    public Yo me(JwtAuthenticationToken auth) {
        var jwt = auth.getToken();
        String oid = jwt.getClaimAsString("oid");
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
        return new Yo(oid != null ? oid : jwt.getSubject(), jwt.getClaimAsString("name"), email, roles);
    }
}
