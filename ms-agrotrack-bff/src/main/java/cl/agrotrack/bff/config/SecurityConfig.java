package cl.agrotrack.bff.config;

import cl.agrotrack.bff.cuenta.EstadoCuentas;
import cl.agrotrack.bff.cuenta.FiltroCuentaActiva;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.HeaderWriterFilter;
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

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private static final java.util.Set<String> PERFILES_PRODUCCION = java.util.Set.of("prod", "production", "aws");

    private final List<String> origenesCors;
    private final boolean esProduccion;

    @Autowired(required = false)
    private RateLimiterFilter rateLimiterFilter;

    @Autowired(required = false)
    private LoggingFilter loggingFilter;

    public SecurityConfig(
            @Value("${agrotrack.cors.origenes}") String origenes,
            @Value("${spring.profiles.active:local}") String activeProfile) {
        // "aws" es el perfil del despliegue real; se aceptan varios perfiles separados por coma
        this.esProduccion = Arrays.stream(activeProfile.split(","))
                .map(String::trim)
                .anyMatch(p -> PERFILES_PRODUCCION.contains(p.toLowerCase()));
        List<String> raw = Arrays.stream(origenes.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.origenesCors = validarYFiltrarOrigenes(raw, this.esProduccion);
        if (this.esProduccion) {
            log.info("Perfil de produccion activo: CORS restringido estrictamente a HTTPS ({})", this.origenesCors);
        }
    }

    public static List<String> validarYFiltrarOrigenes(List<String> origenes, boolean esProduccion) {
        if (!esProduccion) {
            return origenes;
        }
        return origenes.stream()
                .filter(o -> o.toLowerCase().startsWith("https://"))
                .toList();
    }

    /**
     * Segunda capa, despues de la matriz de roles: FiltroCuentaActiva exige
     * ademas que la cuenta este ACTIVA en ms-agrotrack-users.
     */
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, EstadoCuentas cuentas, ObjectMapper json) throws Exception {
        if (loggingFilter != null) {
            http.addFilterBefore(loggingFilter, HeaderWriterFilter.class);
        }
        if (rateLimiterFilter != null) {
            http.addFilterBefore(rateLimiterFilter, UsernamePasswordAuthenticationFilter.class);
        }

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

                        // Usuarios: la ficha propia la edita el productor; administrar es del admin.
                        // POST /api/users/sincronizar no figura: lo llama el BFF desde /api/me.
                        .requestMatchers(HttpMethod.GET, "/api/users/me").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/users/*/perfil").hasAnyRole("ADMIN", "CLIENTE")
                        .requestMatchers(HttpMethod.PUT, "/api/users/*/estado").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/users", "/api/users/*").hasRole("ADMIN")

                        // Lo que no esta en la matriz no pasa, tenga el rol que tenga
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new JwtRolesConverter())))
                // Despues de la matriz: solo se consulta la cuenta de quien ya tiene el rol.
                .addFilterAfter(new FiltroCuentaActiva(cuentas, json), AuthorizationFilter.class)
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

    public List<String> getOrigenesCors() {
        return origenesCors;
    }

    public boolean isEsProduccion() {
        return esProduccion;
    }
}
