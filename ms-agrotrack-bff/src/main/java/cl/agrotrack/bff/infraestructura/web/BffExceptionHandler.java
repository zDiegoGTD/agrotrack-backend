package cl.agrotrack.bff.infraestructura.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.URI;

/**
 * Manejador global de excepciones para el BFF.
 * Estandariza todas las respuestas de error en formato RFC 7807 (application/problem+json)
 * asegurando la presencia del statusCode y evitando fugas de información interna.
 */
@RestControllerAdvice
public class BffExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(BffExceptionHandler.class);

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ProblemDetail> handleIOException(IOException e) {
        log.error("Error de entrada/salida en comunicación de red o proxy: {}", e.getMessage(), e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Fallo de comunicación de red al procesar la solicitud");
        problem.setTitle("Bad Gateway");
        problem.setType(URI.create("about:blank"));
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<ProblemDetail> handleNullPointerException(NullPointerException e) {
        log.error("Referencia nula inesperada durante el procesamiento: {}", e.getMessage(), e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno del servidor al procesar la solicitud");
        problem.setTitle("Internal Server Error");
        problem.setType(URI.create("about:blank"));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ProblemDetail> handleResourceAccessException(ResourceAccessException e) {
        log.error("Error de acceso a recurso o timeout hacia servicio downstream: {}", e.getMessage(), e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "El microservicio de destino no responde o excedió el tiempo límite");
        problem.setTitle("Service Unavailable");
        problem.setType(URI.create("about:blank"));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGenericException(Exception e) {
        log.error("Excepción genérica no controlada en BFF: {}", e.getMessage(), e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Ha ocurrido un error inesperado en el sistema");
        problem.setTitle("Internal Server Error");
        problem.setType(URI.create("about:blank"));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
