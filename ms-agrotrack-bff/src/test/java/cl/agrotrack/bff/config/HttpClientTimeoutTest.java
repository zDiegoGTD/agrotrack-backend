package cl.agrotrack.bff.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests de timeout HTTP para validar que las peticiones downstream no se queden
 * bloqueadas indefinidamente y corten en menos de 11 segundos.
 */
class HttpClientTimeoutTest {

    private HttpServer server;
    private int port;

    @BeforeEach
    void iniciarServidor() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void detenerServidor() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Test 1: Petición a servicio downstream lento corta por timeout en menos de 11 segundos")
    void peticionLentaCortaPorTimeoutEnMenosDe11Segundos() {
        // Servidor simula lentitud artificial (demora 5 segundos)
        server.createContext("/api/lento", exchange -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
            }
            byte[] response = "{\"ok\":true}".getBytes();
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        // Configuramos timeout de lectura a 1.000 ms (1 segundo, estrictamente < 11 segundos)
        HttpClientConfig config = new HttpClientConfig(1000, 1000);
        ClientHttpRequestFactory factory = config.clientHttpRequestFactory();
        RestClient client = RestClient.builder().requestFactory(factory).build();

        long inicio = System.currentTimeMillis();
        assertThatThrownBy(() ->
                client.get()
                        .uri("http://localhost:" + port + "/api/lento")
                        .retrieve()
                        .toBodilessEntity()
        ).isInstanceOf(ResourceAccessException.class);

        long duracion = System.currentTimeMillis() - inicio;

        // Validamos que el corte ocurrió en menos de 11.000 ms
        assertThat(duracion)
                .as("El timeout debe cortar en menos de 11 segundos")
                .isLessThan(11000);
        assertThat(duracion)
                .as("El timeout debe haber ocurrido aproximadamente alrededor de 1 segundo")
                .isGreaterThanOrEqualTo(900);
    }

    @Test
    @DisplayName("Test 2: Petición normal y rápida responde 200 OK dentro del tiempo límite")
    void peticionNormalRespondeExitosamente() {
        server.createContext("/api/rapido", exchange -> {
            byte[] response = "{\"status\":\"UP\"}".getBytes();
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        HttpClientConfig config = new HttpClientConfig(3000, 5000);
        ClientHttpRequestFactory factory = config.clientHttpRequestFactory();
        RestClient client = RestClient.builder().requestFactory(factory).build();

        long inicio = System.currentTimeMillis();
        ResponseEntity<String> response = client.get()
                .uri("http://localhost:" + port + "/api/rapido")
                .retrieve()
                .toEntity(String.class);

        long duracion = System.currentTimeMillis() - inicio;

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("{\"status\":\"UP\"}");
        assertThat(duracion).isLessThan(3000);
    }
}
