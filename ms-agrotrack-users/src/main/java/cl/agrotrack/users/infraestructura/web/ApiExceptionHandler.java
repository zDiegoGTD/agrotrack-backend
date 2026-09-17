package cl.agrotrack.users.infraestructura.web;

import cl.agrotrack.users.aplicacion.RecursoNoEncontradoException;
import cl.agrotrack.users.aplicacion.RutDuplicadoException;
import cl.agrotrack.users.dominio.CambioEstadoInvalidoException;
import cl.agrotrack.users.dominio.MotivoRechazoCambio;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/** Errores como RFC 7807, iguales en todos los servicios, con "codigo" cuando hay regla de negocio. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(RecursoNoEncontradoException.class)
    ProblemDetail noEncontrado(RecursoNoEncontradoException e) {
        return problema(HttpStatus.NOT_FOUND, e.getMessage(), null);
    }

    @ExceptionHandler(CambioEstadoInvalidoException.class)
    ProblemDetail cambioInvalido(CambioEstadoInvalidoException e) {
        // Falta un dato: 400. La regla lo impide: 409.
        HttpStatus status = e.motivo() == MotivoRechazoCambio.MOTIVO_OBLIGATORIO ? HttpStatus.BAD_REQUEST : HttpStatus.CONFLICT;
        return problema(status, e.getMessage(), e.motivo().name());
    }

    @ExceptionHandler(RutDuplicadoException.class)
    ProblemDetail rutDuplicado(RutDuplicadoException e) {
        return problema(HttpStatus.CONFLICT, e.getMessage(), "RUT_DUPLICADO");
    }

    /** Dos admins tocando la misma cuenta a la vez, o dos fichas guardadas con el mismo RUT en paralelo. */
    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, DataIntegrityViolationException.class})
    ProblemDetail conflictoConcurrente(RuntimeException e) {
        return problema(HttpStatus.CONFLICT, "Otro cambio se guardo antes; recarga e intenta de nuevo", "CONFLICTO_CONCURRENTE");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail parametroInvalido(MethodArgumentTypeMismatchException e) {
        return problema(HttpStatus.BAD_REQUEST, "Valor invalido para " + e.getName() + ": " + e.getValue(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validacion(MethodArgumentNotValidException e) {
        Map<String, String> campos = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(fe -> campos.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        ProblemDetail p = problema(HttpStatus.BAD_REQUEST, "Datos invalidos", null);
        p.setProperty("campos", campos);
        return p;
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
