package cl.agrotrack.deliveries.infraestructura.web;

import cl.agrotrack.deliveries.aplicacion.Excepciones.AccesoDenegado;
import cl.agrotrack.deliveries.aplicacion.Excepciones.CapacidadInsuficiente;
import cl.agrotrack.deliveries.aplicacion.Excepciones.CatalogNoDisponible;
import cl.agrotrack.deliveries.aplicacion.Excepciones.RecursoNoEncontrado;
import cl.agrotrack.deliveries.aplicacion.Excepciones.TransicionNoPermitida;
import cl.agrotrack.deliveries.dominio.MotivoRechazo;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** Errores como RFC 7807 (application/problem+json). */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(RecursoNoEncontrado.class)
    ProblemDetail noEncontrado(RecursoNoEncontrado e) {
        return problema(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(AccesoDenegado.class)
    ProblemDetail accesoDenegado(AccesoDenegado e) {
        return problema(HttpStatus.FORBIDDEN, e.getMessage());
    }

    /**
     * La maquina de estados distingue "no puedes" (403) de "no procede"
     * (409). Se respeta esa distincion en el HTTP: al auditor nunca se le
     * confirma si la transicion habria sido valida.
     */
    @ExceptionHandler(TransicionNoPermitida.class)
    ProblemDetail transicion(TransicionNoPermitida e) {
        HttpStatus status = e.motivo() == MotivoRechazo.ROL_SIN_PERMISO ? HttpStatus.FORBIDDEN : HttpStatus.CONFLICT;
        ProblemDetail p = problema(status, e.getMessage());
        p.setProperty("motivo", e.motivo());
        p.setProperty("estadoActual", e.actual());
        if (status == HttpStatus.CONFLICT) {
            p.setProperty("estadoSolicitado", e.destino());
        }
        return p;
    }

    @ExceptionHandler(CapacidadInsuficiente.class)
    ProblemDetail capacidad(CapacidadInsuficiente e) {
        return problema(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(CatalogNoDisponible.class)
    ProblemDetail catalogCaido(CatalogNoDisponible e) {
        return problema(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
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
