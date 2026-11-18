package cl.agrotrack.kafkaadmin.aplicacion;

/**
 * La operacion pedida no se puede hacer. El {@link #codigo()} viaja en el
 * problem+json, con los mismos codigos que usa ms-agrotrack-mq-admin.
 */
public class AdministracionInvalidaException extends RuntimeException {

    private final Motivo motivo;

    public enum Motivo {
        /** Los topicos que declara la topologia del sistema no se borran ni se reparticionan. */
        RECURSO_DEL_SISTEMA,
        /** El topico o el grupo no existe. */
        NO_EXISTE,
        /** Ya existe, el grupo tiene consumidores activos o el cluster no puede cumplirlo (replicas). */
        CONFLICTO_CON_EXISTENTE,
        /** El valor pedido no es valido para Kafka: reducir particiones, una configuracion desconocida. */
        DATOS_INVALIDOS,
        /** Cualquier otro rechazo del broker. */
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
