package cl.agrotrack.bff.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El puerto del BFF esta abierto en la EC2 porque el API Gateway (HTTP API)
 * solo llega a destinos publicos. Este filtro hace que igual sea la unica
 * puerta: el Gateway agrega una cabecera secreta y lo que no la trae se niega.
 */
class FiltroOrigenGatewayTest {

    private static final String SECRETO = "s3cr3t0-del-gateway";

    private MockHttpServletResponse pasar(FiltroOrigenGateway filtro, MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain cadena = new MockFilterChain();
        filtro.doFilter(req, res, cadena);
        if (cadena.getRequest() != null) {
            res.setStatus(299); // marca: la peticion siguio hacia el BFF
        }
        return res;
    }

    private static MockHttpServletRequest peticion(String ruta, String cabecera) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", ruta);
        if (cabecera != null) r.addHeader(FiltroOrigenGateway.CABECERA, cabecera);
        return r;
    }

    @Test
    @DisplayName("Con la cabecera correcta del Gateway: pasa")
    void conCabecera() throws Exception {
        var f = new FiltroOrigenGateway(SECRETO, new ObjectMapper());
        assertThat(pasar(f, peticion("/api/me", SECRETO)).getStatus()).isEqualTo(299);
    }

    @Test
    @DisplayName("Llamada directa a la EC2 (sin cabecera): 403 ORIGEN_NO_PERMITIDO")
    void directoALaEc2() throws Exception {
        var f = new FiltroOrigenGateway(SECRETO, new ObjectMapper());
        MockHttpServletResponse res = pasar(f, peticion("/api/me", null));

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentType()).startsWith("application/problem+json");
        assertThat(res.getContentAsString()).contains("ORIGEN_NO_PERMITIDO");
    }

    @Test
    @DisplayName("Con un secreto inventado: 403")
    void secretoFalso() throws Exception {
        var f = new FiltroOrigenGateway(SECRETO, new ObjectMapper());
        assertThat(pasar(f, peticion("/api/me", "adivinado")).getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("El health check de Docker (localhost, sin Gateway) sigue funcionando")
    void healthSinCabecera() throws Exception {
        var f = new FiltroOrigenGateway(SECRETO, new ObjectMapper());
        assertThat(pasar(f, peticion("/actuator/health/readiness", null)).getStatus()).isEqualTo(299);
    }

    @Test
    @DisplayName("Sin secreto configurado (desarrollo local): no filtra nada")
    void desactivadoEnLocal() throws Exception {
        var f = new FiltroOrigenGateway("", new ObjectMapper());
        assertThat(pasar(f, peticion("/api/me", null)).getStatus()).isEqualTo(299);
    }
}
