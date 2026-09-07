package cl.agrotrack.notify.aplicacion;

/**
 * El mensaje no se puede interpretar (JSON roto, sin eventId, sin type).
 * Reintentarlo es inutil: va directo a la DLQ, sin pasar por los 3 intentos.
 */
public class MensajeInvalidoException extends RuntimeException {

    public MensajeInvalidoException(String detalle, Throwable causa) {
        super(detalle, causa);
    }

    public MensajeInvalidoException(String detalle) {
        super(detalle);
    }
}
