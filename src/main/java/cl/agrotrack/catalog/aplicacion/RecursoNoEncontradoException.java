package cl.agrotrack.catalog.aplicacion;

/** Se traduce a 404. */
public class RecursoNoEncontradoException extends RuntimeException {

    public RecursoNoEncontradoException(String recurso, Object id) {
        super(recurso + " " + id + " no existe");
    }
}
