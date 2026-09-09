package cl.agrotrack.bff.infraestructura.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reenvia {@code /api/<servicio>/**} al microservicio correspondiente,
 * conservando metodo, ruta, query, cuerpo, Content-Type y el Bearer.
 *
 * <p>Llega aqui solo lo que la matriz de SecurityConfig dejo pasar.
 */
@RestController
public class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

    /** Cabeceras del cliente que SI se reenvian. El resto (Host, Cookie, etc.) no. */
    private static final Set<String> CABECERAS_PERMITIDAS = Set.of(
            "authorization", "content-type", "accept", "accept-language", "traceparent", "tracestate");

    private final Servicios servicios;
    private final Reenviador reenviador;

    public ProxyController(Servicios servicios, Reenviador reenviador) {
        this.servicios = servicios;
        this.reenviador = reenviador;
    }

    @RequestMapping("/api/{servicio}/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request,
                                        @RequestBody(required = false) byte[] cuerpo,
                                        Authentication auth) throws IOException {
        String path = request.getRequestURI();               // /api/deliveries/12/status
        String servicio = path.split("/")[2];                 // deliveries
        String base = servicios.urlDe(servicio);
        if (base == null) {
            return problema(HttpStatus.NOT_FOUND, "No existe el servicio '" + servicio + "'");
        }

        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(base).path(path);
        if (request.getQueryString() != null) {
            b.query(request.getQueryString());
        } else {
            // Sin query string cruda (p. ej. en tests con MockMvc) se reconstruye desde los parametros
            request.getParameterMap().forEach((k, vs) -> {
                for (String v : vs) {
                    b.queryParam(k, v);
                }
            });
        }
        URI destino = b.build().encode().toUri();

        HttpHeaders cabeceras = new HttpHeaders();
        request.getHeaderNames().asIterator().forEachRemaining(nombre -> {
            if (CABECERAS_PERMITIDAS.contains(nombre.toLowerCase())) {
                cabeceras.put(nombre, List.of(request.getHeader(nombre)));
            }
        });
        // El Bearer viaja tal cual; si Spring ya lo valido, es el del usuario.
        if (auth instanceof JwtAuthenticationToken jwt) {
            cabeceras.setBearerAuth(jwt.getToken().getTokenValue());
        }
        cabeceras.set("X-Forwarded-By", "ms-agrotrack-bff");

        HttpMethod metodo = HttpMethod.valueOf(request.getMethod());
        log.debug("{} {} -> {}", metodo, path, destino);
        return reenviador.reenviar(metodo, destino, cabeceras, cuerpo);
    }

    private static ResponseEntity<byte[]> problema(HttpStatus status, String detalle) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detalle);
        String json = "{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase() + "\",\"status\":" + status.value()
                + ",\"detail\":\"" + detalle.replace("\"", "'") + "\"}";
        return ResponseEntity.status(status).header(HttpHeaders.CONTENT_TYPE, "application/problem+json")
                .body(json.getBytes());
    }

    /** agrotrack.servicios.<nombre> = url base. */
    @Component
    @ConfigurationProperties(prefix = "agrotrack")
    public static class Servicios {
        private Map<String, String> servicios = Map.of();

        public Map<String, String> getServicios() {
            return servicios;
        }

        public void setServicios(Map<String, String> servicios) {
            this.servicios = servicios;
        }

        public String urlDe(String nombre) {
            return servicios.get(nombre);
        }
    }
}
