package cl.agrotrack.bff.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Filtro de logging seguro para el BFF.
 * Sanitiza parámetros de consulta y cabeceras sensibles (passwords, tokens, secretos)
 * para evitar la fuga de información confidencial en archivos de registro (CWE-532).
 */
@Component
public class LoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LoggingFilter.class);

    private static final Set<String> PARAMETROS_SENSIBLES = Set.of(
            "password", "pass", "pwd", "token", "access_token", "refresh_token",
            "secret", "secretkey", "api_key", "apikey", "authorization", "auth");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String metodo = request.getMethod();
        String uri = request.getRequestURI();
        String queryOriginal = request.getQueryString();
        String querySanitizada = sanitizarQueryString(queryOriginal);

        String authHeader = request.getHeader("Authorization");
        String authSanitizado = sanitizarCabecera("Authorization", authHeader);

        if (querySanitizada != null && !querySanitizada.isBlank()) {
            log.info("HTTP IN: {} {}?{} [Auth: {}]", metodo, uri, querySanitizada, authSanitizado);
        } else {
            log.info("HTTP IN: {} {} [Auth: {}]", metodo, uri, authSanitizado);
        }

        long inicio = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long duracion = System.currentTimeMillis() - inicio;
            log.info("HTTP OUT: {} {} -> status={} ({} ms)", metodo, uri, response.getStatus(), duracion);
        }
    }

    public static String sanitizarQueryString(String queryString) {
        if (queryString == null || queryString.isBlank()) {
            return queryString;
        }

        // Recorrido lineal por "&" y el primer "=": sin expresiones regulares, una
        // query maliciosa no puede provocar tiempos de proceso desmedidos.
        StringBuilder sb = new StringBuilder();
        String[] partes = queryString.split("&", -1);
        for (int i = 0; i < partes.length; i++) {
            if (i > 0) {
                sb.append('&');
            }
            String parte = partes[i];
            int igual = parte.indexOf('=');
            String clave = igual < 0 ? parte : parte.substring(0, igual);
            if (igual >= 0 && PARAMETROS_SENSIBLES.contains(clave.toLowerCase())) {
                sb.append(clave).append("=***");
            } else {
                sb.append(parte);
            }
        }
        return sb.toString();
    }

    public static String sanitizarCabecera(String nombre, String valor) {
        if (valor == null || valor.isBlank()) {
            return "ninguno";
        }
        if ("authorization".equalsIgnoreCase(nombre)) {
            if (valor.toLowerCase().startsWith("bearer ")) {
                return "Bearer ***";
            }
            return "***";
        }
        return valor;
    }
}
