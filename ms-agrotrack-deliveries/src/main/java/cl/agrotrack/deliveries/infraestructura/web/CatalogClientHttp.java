package cl.agrotrack.deliveries.infraestructura.web;

import cl.agrotrack.deliveries.aplicacion.CatalogClient;
import cl.agrotrack.deliveries.aplicacion.Excepciones.CapacidadInsuficiente;
import cl.agrotrack.deliveries.aplicacion.Excepciones.CatalogNoDisponible;
import cl.agrotrack.deliveries.aplicacion.Excepciones.RecursoNoEncontrado;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Cliente HTTP hacia ms-agrotrack-catalog.
 *
 * <p>Propaga el Bearer del usuario que esta ejecutando la transicion: es
 * su rol (OPERADOR/ADMIN) el que autoriza tocar la capacidad. Deliveries
 * no tiene una identidad propia con mas poder que el usuario.
 */
@Component
public class CatalogClientHttp implements CatalogClient {

    private final RestClient rest;

    public CatalogClientHttp(RestClient.Builder builder, @Value("${agrotrack.catalog.url}") String baseUrl) {
        this.rest = builder.baseUrl(baseUrl).build();
    }

    @Override
    public void verificarProducto(Long productoId) {
        try {
            Map<?, ?> p = rest.get().uri("/api/catalog/productos/{id}", productoId)
                    .headers(this::bearer)
                    .retrieve().body(Map.class);
            if (p == null || Boolean.FALSE.equals(p.get("activo"))) {
                throw new RecursoNoEncontrado("Producto", productoId);
            }
        } catch (HttpClientErrorException.NotFound e) {
            throw new RecursoNoEncontrado("Producto", productoId);
        } catch (ResourceAccessException e) {
            throw new CatalogNoDisponible(e);
        }
    }

    @Override
    public void verificarBodega(Long bodegaId) {
        try {
            rest.get().uri("/api/catalog/bodegas/{id}", bodegaId)
                    .headers(this::bearer)
                    .retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.NotFound e) {
            throw new RecursoNoEncontrado("Bodega", bodegaId);
        } catch (ResourceAccessException e) {
            throw new CatalogNoDisponible(e);
        }
    }

    @Override
    public void reservarCapacidad(Long bodegaId, BigDecimal cantidad, String entregaCodigo) {
        capacidad("reservar", bodegaId, cantidad, entregaCodigo);
    }

    @Override
    public void liberarCapacidad(Long bodegaId, BigDecimal cantidad, String entregaCodigo) {
        capacidad("liberar", bodegaId, cantidad, entregaCodigo);
    }

    private void capacidad(String operacion, Long bodegaId, BigDecimal cantidad, String entregaCodigo) {
        try {
            rest.post().uri("/api/catalog/bodegas/{id}/capacidad/{op}", bodegaId, operacion)
                    .headers(this::bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("cantidad", cantidad, "entregaCodigo", entregaCodigo))
                    .retrieve().toBodilessEntity();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                throw new CapacidadInsuficiente(detalle(e, "La bodega no tiene capacidad suficiente"));
            }
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new RecursoNoEncontrado("Bodega", bodegaId);
            }
            throw e;
        } catch (ResourceAccessException e) {
            throw new CatalogNoDisponible(e);
        }
    }

    private void bearer(HttpHeaders headers) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            headers.setBearerAuth(jwt.getToken().getTokenValue());
        }
    }

    /** Catalog responde problem+json; el campo detail trae el mensaje legible. */
    private static String detalle(HttpClientErrorException e, String porDefecto) {
        try {
            Map<?, ?> problem = e.getResponseBodyAs(Map.class);
            Object d = problem != null ? problem.get("detail") : null;
            return d != null ? d.toString() : porDefecto;
        } catch (RuntimeException ignored) {
            return porDefecto;
        }
    }
}
