package cl.agrotrack.bff.infraestructura.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests para el manejador global de excepciones BffExceptionHandler.
 * Valida que todo error retorne un ProblemDetail (RFC 7807) en JSON con su statusCode correspondiente.
 */
class BffExceptionHandlerTest {

    private final BffExceptionHandler handler = new BffExceptionHandler();

    @Test
    @DisplayName("Test 1: IOException produce 502 Bad Gateway con ProblemDetail y statusCode 502")
    void testHandleIOException() {
        IOException ex = new IOException("Error de conexión por socket roto con microservicio");
        ResponseEntity<ProblemDetail> response = handler.handleIOException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);

        ProblemDetail problem = response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getStatus()).isEqualTo(502);
        assertThat(problem.getTitle()).isEqualTo("Bad Gateway");
        assertThat(problem.getDetail()).contains("Fallo de comunicación de red");
    }

    @Test
    @DisplayName("Test 2: NullPointerException produce 500 Internal Server Error con mensaje sanitizado")
    void testHandleNullPointerException() {
        NullPointerException ex = new NullPointerException("Atributo de sesión nulo");
        ResponseEntity<ProblemDetail> response = handler.handleNullPointerException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);

        ProblemDetail problem = response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getStatus()).isEqualTo(500);
        assertThat(problem.getTitle()).isEqualTo("Internal Server Error");
        assertThat(problem.getDetail()).isEqualTo("Error interno del servidor al procesar la solicitud");
    }

    @Test
    @DisplayName("Test 3: ResourceAccessException produce 503 Service Unavailable")
    void testHandleResourceAccessException() {
        ResourceAccessException ex = new ResourceAccessException("I/O error on POST request for http://localhost:8082: Read timed out");
        ResponseEntity<ProblemDetail> response = handler.handleResourceAccessException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);

        ProblemDetail problem = response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getStatus()).isEqualTo(503);
        assertThat(problem.getTitle()).isEqualTo("Service Unavailable");
        assertThat(problem.getDetail()).contains("El microservicio de destino no responde");
    }

    @Test
    @DisplayName("Test 4: Excepción genérica produce 500 con formato RFC 7807 y statusCode 500")
    void testHandleGenericException() {
        RuntimeException ex = new IllegalStateException("Estado inconsistente en memoria");
        ResponseEntity<ProblemDetail> response = handler.handleGenericException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);

        ProblemDetail problem = response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getStatus()).isEqualTo(500);
        assertThat(problem.getTitle()).isEqualTo("Internal Server Error");
        assertThat(problem.getDetail()).isEqualTo("Ha ocurrido un error inesperado en el sistema");
    }
}
