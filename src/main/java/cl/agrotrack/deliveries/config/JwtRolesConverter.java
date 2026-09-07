package cl.agrotrack.deliveries.config;

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
        String oid = jwt.getClaimAsString("oid");
        return new JwtAuthenticationToken(jwt, authorities, oid != null ? oid : jwt.getSubject());
    }
}
