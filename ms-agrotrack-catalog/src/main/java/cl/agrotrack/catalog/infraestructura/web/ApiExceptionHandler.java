package cl.agrotrack.catalog.infraestructura.web;

import cl.agrotrack.catalog.aplicacion.CapacidadService.ConflictoConcurrenteException;
import cl.agrotrack.catalog.aplicacion.CatalogService.CodigoDuplicadoException;
import cl.agrotrack.catalog.aplicacion.RecursoNoEncontradoException;
import cl.agrotrack.catalog.dominio.CapacidadInsuficienteException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** Errores como RFC 7807 (application/problem+json), iguales en todos los servicios. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(RecursoNoEncontradoException.class)
    ProblemDetail noEncontrado(RecursoNoEncontradoException e) {
        return problema(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(CapacidadInsuficienteException.class)
    ProblemDetail capacidadInsuficiente(CapacidadInsuficienteException e) {
        ProblemDetail p = problema(HttpStatus.CONFLICT, e.getMessage());
        p.setProperty("disponible", e.disponible());
        p.setProperty("solicitada", e.solicitada());
        return p;
    }

    @ExceptionHandler({CodigoDuplicadoException.class, ConflictoConcurrenteException.class})
    ProblemDetail conflicto(RuntimeException e) {
        return problema(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail argumentoInvalido(IllegalArgumentException e) {
        return problema(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validacion(MethodArgumentNotValidException e) {
        Map<String, String> campos = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(fe -> campos.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        ProblemDetail p = problema(HttpStatus.BAD_REQUEST, "Datos invalidos");
        p.setProperty("campos", campos);
        return p;
    }

    private static ProblemDetail problema(HttpStatus status, String detalle) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detalle);
        p.setTitle(status.getReasonPhrase());
        return p;
    }
}
