package cl.agrotrack.bff.cuenta;

import cl.agrotrack.bff.infraestructura.web.ProxyController;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

@Component
public class ClienteUsuariosHttp implements ClienteUsuarios {

    private final RestClient rest;
    private final ProxyController.Servicios servicios;

    public ClienteUsuariosHttp(RestClient.Builder builder, ProxyController.Servicios servicios) {
        // Timeouts cortos: esta llamada va delante de cada request de un usuario.
        // Sin ellos, un users colgado dejaria colgado a todo el BFF.
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(Duration.ofSeconds(2));
        fabrica.setReadTimeout(Duration.ofSeconds(5));
        this.rest = builder.requestFactory(fabrica).build();
        this.servicios = servicios;
    }

    @Override
    public CuentaUsuario sincronizar(String bearer) {
        String base = servicios.urlDe("users");
        if (base == null) {
            throw new UsuariosNoDisponibleException("agrotrack.servicios.users no esta configurado", null);
        }
        try {
            CuentaUsuario cuenta = rest.post()
                    .uri(base + "/api/users/sincronizar")
                    .headers(h -> h.setBearerAuth(bearer))
                    .retrieve()
                    .body(CuentaUsuario.class);
            if (cuenta == null || cuenta.estado() == null) {
                throw new UsuariosNoDisponibleException("ms-agrotrack-users respondio sin estado", null);
            }
            return cuenta;
        } catch (RestClientException e) {
            throw new UsuariosNoDisponibleException("ms-agrotrack-users no respondio: " + e.getMessage(), e);
        }
    }
}
