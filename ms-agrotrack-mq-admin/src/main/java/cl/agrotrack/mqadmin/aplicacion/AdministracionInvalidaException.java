package cl.agrotrack.mqadmin.aplicacion;

/**
 * La operacion pedida no se puede hacer: el recurso es del sistema, ya existe
 * con otros atributos, o no existe. El {@link #codigo()} viaja en el
 * problem+json para que el frontend sepa por que se rechazo.
 */
public class AdministracionInvalidaException extends RuntimeException {

    private final Motivo motivo;

    public enum Motivo {
        /** Las colas y exchanges que declara la topologia del sistema no se tocan. */
        RECURSO_DEL_SISTEMA,
        /** Borrar algo que no existe: el cliente cree que hay algo que no hay. */
        NO_EXISTE,
        /** Declarar con atributos distintos a los que ya tiene el broker. */
        CONFLICTO_CON_EXISTENTE,
        /** El broker rechazo la operacion (permisos, nombre reservado, vhost). */
        BROKER_RECHAZO
    }

    public AdministracionInvalidaException(Motivo motivo, String mensaje) {
        super(mensaje);
        this.motivo = motivo;
    }

    public Motivo motivo() {
        return motivo;
    }

    public String codigo() {
        return motivo.name();
    }
}
