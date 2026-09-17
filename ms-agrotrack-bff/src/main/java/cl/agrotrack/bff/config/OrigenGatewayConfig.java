package cl.agrotrack.bff.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class OrigenGatewayConfig {

    /** Primero de todos: una peticion que no vino por el Gateway no llega ni a validar el token. */
    @Bean
    FilterRegistrationBean<FiltroOrigenGateway> filtroOrigenGateway(
            @Value("${agrotrack.gateway.secreto:}") String secreto, ObjectMapper json) {
        FilterRegistrationBean<FiltroOrigenGateway> registro = new FilterRegistrationBean<>(new FiltroOrigenGateway(secreto, json));
        registro.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registro;
    }
}
