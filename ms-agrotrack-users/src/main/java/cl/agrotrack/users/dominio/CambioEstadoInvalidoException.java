package cl.agrotrack.users.dominio;

public class CambioEstadoInvalidoException extends RuntimeException {

    private final MotivoRechazoCambio motivo;

    public CambioEstadoInvalidoException(MotivoRechazoCambio motivo, String mensaje) {
        super(mensaje);
        this.motivo = motivo;
    }

    public MotivoRechazoCambio motivo() {
        return motivo;
    }
}
