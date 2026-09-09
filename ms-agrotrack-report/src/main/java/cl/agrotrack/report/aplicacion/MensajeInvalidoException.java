package cl.agrotrack.report.aplicacion;

/** El evento no se puede interpretar. No se reintenta: va directo al DLT. */
public class MensajeInvalidoException extends RuntimeException {

    public MensajeInvalidoException(String detalle, Throwable causa) {
        super(detalle, causa);
    }

    public MensajeInvalidoException(String detalle) {
        super(detalle);
    }
}
