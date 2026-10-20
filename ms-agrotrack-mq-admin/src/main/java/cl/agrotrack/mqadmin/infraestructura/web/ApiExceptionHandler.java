package cl.agrotrack.mqadmin.infraestructura.web;

import cl.agrotrack.mqadmin.aplicacion.AdministracionInvalidaException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.LinkedHashMap;
import java.util.Map;

/** Errores como RFC 7807, iguales en todos los servicios, con "codigo" cuando hay regla de negocio. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AdministracionInvalidaException.class)
    ProblemDetail administracionInvalida(AdministracionInvalidaException e) {
        HttpStatus status = switch (e.motivo()) {
            case NO_EXISTE -> HttpStatus.NOT_FOUND;
            case RECURSO_DEL_SISTEMA -> HttpStatus.FORBIDDEN;
            case CONFLICTO_CON_EXISTENTE -> HttpStatus.CONFLICT;
            case BROKER_RECHAZO -> HttpStatus.BAD_GATEWAY;
        };
        return problema(status, e.getMessage(), e.codigo());
    }

    /** Cuerpo con campos invalidos: se devuelve cada campo con su motivo. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validacion(MethodArgumentNotValidException e) {
        Map<String, String> campos = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(fe -> campos.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        ProblemDetail p = problema(HttpStatus.BAD_REQUEST, "Datos invalidos", "DATOS_INVALIDOS");
        p.setProperty("campos", campos);
        return p;
    }

    /** Parametro de ruta invalido, p. ej. DELETE /queues/amq.algo. */
    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class})
    ProblemDetail parametroInvalido(Exception e) {
        return problema(HttpStatus.BAD_REQUEST, "Nombre invalido: " + mensaje(e), "DATOS_INVALIDOS");
    }

    /** JSON roto o un tipo de exchange que no existe ("tipo": "headers"). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail cuerpoIlegible(HttpMessageNotReadableException e) {
        return problema(HttpStatus.BAD_REQUEST,
                "El cuerpo no es un JSON valido o trae un valor no admitido (tipo: DIRECT, TOPIC o FANOUT)",
                "DATOS_INVALIDOS");
    }

    private static String mensaje(Exception e) {
        if (e instanceof HandlerMethodValidationException h) {
            return h.getAllErrors().stream()
                    .map(err -> err.getDefaultMessage())
                    .findFirst().orElse("valor no admitido");
        }
        return e.getMessage();
    }

    private static ProblemDetail problema(HttpStatus status, String detalle, String codigo) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detalle);
        p.setTitle(status.getReasonPhrase());
        if (codigo != null) {
            p.setProperty("codigo", codigo);
        }
        return p;
    }
}
