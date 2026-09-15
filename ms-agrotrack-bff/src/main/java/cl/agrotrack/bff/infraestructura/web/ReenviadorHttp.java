package cl.agrotrack.bff.infraestructura.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Reenvio con RestClient. Las respuestas 4xx/5xx del servicio se devuelven
 * al cliente sin tocarlas (incluido el problem+json): el BFF no reinterpreta
 * los errores de dominio. Solo cuando el servicio no responde, el BFF
 * fabrica un 503 propio.
 */
@Component
public class ReenviadorHttp implements Reenviador {

    private static final Logger log = LoggerFactory.getLogger(ReenviadorHttp.class);

    private final RestClient rest;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public ReenviadorHttp(RestClient.Builder builder, ObjectMapper objectMapper) {
        this.rest = builder.build();
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public ReenviadorHttp(RestClient.Builder builder) {
        this(builder, new ObjectMapper());
    }

    @Override
    public ResponseEntity<byte[]> reenviar(HttpMethod metodo, URI destino, HttpHeaders cabeceras, byte[] cuerpo) {
        try {
            RestClient.RequestBodySpec req = rest.method(metodo).uri(destino).headers(h -> h.addAll(cabeceras));
            if (cuerpo != null && cuerpo.length > 0) {
                req.body(cuerpo);
            }
            return req.exchange((request, response) -> {
                HttpHeaders salida = new HttpHeaders();
                response.getHeaders().forEach((k, v) -> {
                    // Cabeceras hop-by-hop no se propagan
                    if (!k.equalsIgnoreCase(HttpHeaders.TRANSFER_ENCODING) && !k.equalsIgnoreCase(HttpHeaders.CONNECTION)) {
                        salida.put(k, v);
                    }
                });
                byte[] body = response.getBody().readAllBytes();
                return ResponseEntity.status(response.getStatusCode()).headers(salida).body(body);
            }, false);
        } catch (ResourceAccessException e) {
            log.error("Servicio no disponible: {} {} -> {}", metodo, destino, e.getMessage());
            ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                    "El servicio " + destino.getHost() + ":" + destino.getPort() + " no responde");
            p.setTitle("Service Unavailable");
            p.setType(URI.create("about:blank"));
            byte[] bodyBytes;
            try {
                bodyBytes = objectMapper.writeValueAsBytes(p);
            } catch (Exception ex) {
                log.error("Error serializando ProblemDetail en ReenviadorHttp", ex);
                bodyBytes = "{\"type\":\"about:blank\",\"title\":\"Service Unavailable\",\"status\":503}".getBytes(StandardCharsets.UTF_8);
            }
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(bodyBytes);
        }
    }
}
