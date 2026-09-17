package cl.agrotrack.bff.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Filtro de limitación de tasa (Rate Limiting) por dirección IP.
 * Protege al BFF y microservicios downstream contra ataques de fuerza bruta
 * y denegación de servicio (DoS) limitando a 100 peticiones por minuto por IP.
 */
@Component
public class RateLimiterFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterFilter.class);

    private final int maxRequestsPerMinute;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, RequestCounter> requestCounts = new ConcurrentHashMap<>();

    public RateLimiterFilter(
            @Value("${agrotrack.rate-limit.max-requests-per-minute:100}") int maxRequestsPerMinute,
            ObjectMapper objectMapper) {
        this.maxRequestsPerMinute = maxRequestsPerMinute;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public RateLimiterFilter() {
        this(100, new ObjectMapper());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // Permitir preflight OPTIONS y actuator health sin limitar
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || request.getRequestURI().startsWith("/actuator/health")) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = extraerIpCliente(request);
        long ventanaActual = System.currentTimeMillis() / 60000;

        RequestCounter counter = requestCounts.compute(ip, (k, existing) -> {
            if (existing == null || existing.ventanaMinuto != ventanaActual) {
                return new RequestCounter(ventanaActual, new AtomicInteger(1));
            }
            existing.contador.incrementAndGet();
            return existing;
        });

        if (counter.contador.get() > maxRequestsPerMinute) {
            log.warn("Rate limit excedido para IP: {}. Conteo: {} > {}", ip, counter.contador.get(), maxRequestsPerMinute);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setHeader("Retry-After", "60");

            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Demasiadas solicitudes. Límite de " + maxRequestsPerMinute + " peticiones por minuto excedido.");
            problem.setTitle("Too Many Requests");
            problem.setType(URI.create("about:blank"));

            byte[] json = objectMapper.writeValueAsBytes(problem);
            response.getOutputStream().write(json);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * El API Gateway agrega la IP real del cliente AL FINAL de X-Forwarded-For;
     * lo que venga antes lo escribio el propio cliente y puede ser inventado.
     * Tomar el primer valor permitiria cambiarlo en cada peticion y no llegar
     * nunca al limite.
     */
    private String extraerIpCliente(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            String[] saltos = xForwardedFor.split(",");
            return saltos[saltos.length - 1].trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "127.0.0.1";
    }

    public void limpiarContadores() {
        requestCounts.clear();
    }

    public int getMaxRequestsPerMinute() {
        return maxRequestsPerMinute;
    }

    private static class RequestCounter {
        final long ventanaMinuto;
        final AtomicInteger contador;

        RequestCounter(long ventanaMinuto, AtomicInteger contador) {
            this.ventanaMinuto = ventanaMinuto;
            this.contador = contador;
        }
    }
}
