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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern QUERY_PARAM_PATTERN = Pattern.compile("([^&=?]+)=([^&]*)");

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

        Matcher matcher = QUERY_PARAM_PATTERN.matcher(queryString);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String val = matcher.group(2);

            String reemplazo;
            if (PARAMETROS_SENSIBLES.contains(key.toLowerCase())) {
                reemplazo = key + "=***";
            } else {
                reemplazo = key + "=" + val;
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(reemplazo));
        }
        matcher.appendTail(sb);
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
