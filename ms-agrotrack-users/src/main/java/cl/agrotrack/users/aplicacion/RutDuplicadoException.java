package cl.agrotrack.users.aplicacion;

/** Se traduce a 409. */
public class RutDuplicadoException extends RuntimeException {

    public RutDuplicadoException(String rut) {
        super("El RUT " + rut + " ya pertenece a otra ficha");
    }
}
