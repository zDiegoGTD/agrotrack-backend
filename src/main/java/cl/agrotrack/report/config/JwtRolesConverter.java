package cl.agrotrack.report.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.List;

/**
 * Traduce el claim {@code roles} del token (App Roles de Azure AD) a
 * autoridades {@code ROLE_<rol>}, que es lo que entiende {@code hasRole}.
 *
 * <p>Azure entrega los roles como lista de strings en el claim "roles".
 * Los tokens locales de mint.mjs imitan exactamente esa forma.
 */
public class JwtRolesConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        Collection<GrantedAuthority> authorities = roles == null
                ? List.of()
                : roles.stream()
                        .map(String::toUpperCase)
                        .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r))
                        .toList();
        return new JwtAuthenticationToken(jwt, authorities, nombreUsuario(jwt));
    }

    private static String nombreUsuario(Jwt jwt) {
        String oid = jwt.getClaimAsString("oid");
        return oid != null ? oid : jwt.getSubject();
    }
}
