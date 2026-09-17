package cl.agrotrack.users.aplicacion;

public class RecursoNoEncontradoException extends RuntimeException {

    public RecursoNoEncontradoException(String recurso, Object id) {
        super(recurso + " " + id + " no existe");
    }
}
