package cl.agrotrack.bff.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * La regla del enunciado: <i>"Spring Security: validar el JWT y comprobar
 * que el rol puede usar el endpoint llamado"</i>. Esta es esa matriz. Cada
 * servicio de dominio vuelve a comprobarlo (defensa en profundidad), pero
 * un rol sin permiso no llega ni a salir del BFF.
 *
 * <p>Flujo: JWT -> API Gateway -> bff -> microservicio. El Bearer se
 * reenvia tal cual; el BFF no tiene una identidad propia mas poderosa que
 * la del usuario.
 */
@Configuration
public class SecurityConfig {

    private final List<String> origenesCors;

    public SecurityConfig(@Value("${agrotrack.cors.origenes}") String origenes) {
        this.origenesCors = Arrays.stream(origenes.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        .requestMatchers("/api/me").authenticated()

                        // Entregas (seccion 6): todos ven; registra productor/operador/admin; cambia estado operador/admin
                        .requestMatchers(HttpMethod.GET, "/api/deliveries/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/deliveries").hasAnyRole("ADMIN", "OPERADOR", "CLIENTE")
                        .requestMatchers(HttpMethod.PUT, "/api/deliveries/*/status").hasAnyRole("ADMIN", "OPERADOR")

                        // Catalogo: leer cualquiera autenticado; capacidad operador/admin; escribir solo admin
                        .requestMatchers(HttpMethod.GET, "/api/catalog/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/catalog/bodegas/*/capacidad/**").hasAnyRole("ADMIN", "OPERADOR")
                        .requestMatchers("/api/catalog/**").hasRole("ADMIN")

                        // Reporteria: solo admin. Auditoria: admin y auditor (solo lectura)
                        .requestMatchers("/api/report/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/audit/**").hasAnyRole("ADMIN", "AUDITOR")

                        // Estado de la mensajeria: paneles de administracion
                        .requestMatchers(HttpMethod.GET, "/api/mq/**", "/api/kafka/**").hasAnyRole("ADMIN", "OPERADOR")

                        // Lo que no esta en la matriz no pasa, tenga el rol que tenga
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new JwtRolesConverter())))
                .build();
    }

    /** El frontend Angular corre en otro origen (4200 en local, el bucket/CloudFront en AWS). */
    @Bean
    CorsConfigurationSource corsSource() {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(origenesCors);
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "traceparent"));
        c.setExposedHeaders(List.of("Location", "Content-Type"));
        c.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", c);
        return source;
    }
}
