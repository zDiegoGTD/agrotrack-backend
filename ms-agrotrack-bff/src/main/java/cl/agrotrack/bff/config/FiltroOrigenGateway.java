package cl.agrotrack.bff.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Hace del API Gateway la unica puerta al BFF. El HTTP API de AWS solo llega
 * a destinos publicos, asi que el puerto del BFF esta abierto en la EC2; sin
 * este filtro se podria llamar directo y saltarse el Gateway. El Gateway
 * agrega {@value #CABECERA} con un secreto (infra/aws/gateway.ps1) y aqui se
 * niega lo que no lo traiga.
 *
 * <p>Sin secreto configurado (desarrollo local) no filtra nada.
 */
public class FiltroOrigenGateway extends OncePerRequestFilter {

    public static final String CABECERA = "X-Origen-Gateway";

    private static final Logger log = LoggerFactory.getLogger(FiltroOrigenGateway.class);

    private final byte[] secreto;
    private final ObjectMapper json;

    public FiltroOrigenGateway(String secreto, ObjectMapper json) {
        this.secreto = secreto == null ? new byte[0] : secreto.getBytes(StandardCharsets.UTF_8);
        this.json = json;
    }

    /** El health check lo hace Docker desde dentro de la EC2, sin pasar por el Gateway. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return secreto.length == 0 || request.getRequestURI().startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String recibido = request.getHeader(CABECERA);
        // Comparacion en tiempo constante: no filtra cuantos caracteres acerto un intento
        if (recibido != null && MessageDigest.isEqual(secreto, recibido.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("Peticion sin pasar por el API Gateway: {} {} desde {}", request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                "Esta API solo se consume a través del API Gateway.");
        p.setTitle("Forbidden");
        p.setProperty("codigo", "ORIGEN_NO_PERMITIDO");
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), p);
    }
}
