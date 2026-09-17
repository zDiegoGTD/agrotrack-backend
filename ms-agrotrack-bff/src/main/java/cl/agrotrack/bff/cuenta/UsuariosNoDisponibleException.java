package cl.agrotrack.bff.cuenta;

/** users no respondio o respondio mal. El BFF lo traduce a 503: ante la duda, no se deja pasar. */
public class UsuariosNoDisponibleException extends RuntimeException {

    public UsuariosNoDisponibleException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
