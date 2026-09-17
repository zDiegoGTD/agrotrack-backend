package cl.agrotrack.bff.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Arrays;

/**
 * Validaciones del token que se suman a las de Spring (firma, emisor,
 * audiencia, exp y nbf). Spring Boot agrega solo al JwtDecoder cualquier
 * bean OAuth2TokenValidator&lt;Jwt&gt;.
 */
@Configuration
public class ValidacionTokenConfig {

    /**
     * La pauta pide leer roles Y scopes del token. El rol decide que puede
     * hacer el usuario; el scope, que el token se emitio para usar esta API
     * en nombre del usuario (access_as_user). Solo en AWS: el token de
     * desarrollo de mint.mjs tambien lo trae, pero no se exige en local.
     */
    @Bean
    @ConditionalOnProperty("agrotrack.seguridad.scope-requerido")
    OAuth2TokenValidator<Jwt> validadorDeScope(@Value("${agrotrack.seguridad.scope-requerido}") String requerido) {
        OAuth2Error error = new OAuth2Error("invalid_token", "Falta el scope requerido: " + requerido, null);
        return jwt -> {
            String scp = jwt.getClaimAsString("scp");
            boolean tiene = scp != null && Arrays.asList(scp.split(" ")).contains(requerido);
            return tiene ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(error);
        };
    }
}
