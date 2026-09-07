package cl.agrotrack.deliveries.dominio;

/**
 * Estados del ciclo de vida de una entrega.
 *
 * <p>Sin tilde en EN_CLASIFICACION a proposito: la tilde vive solo en la
 * etiqueta que ve el usuario. Ver docs/00-decisiones.md (D3).
 */
public enum EstadoEntrega {

    REGISTRADA("Registrada"),
    RECIBIDA("Recibida"),
    EN_CLASIFICACION("En clasificacion"),
    EN_DESPACHO("En despacho"),
    DESPACHADA("Despachada", true),
    RECHAZADA("Rechazada", true);

    private final String etiqueta;
    private final boolean terminal;

    EstadoEntrega(String etiqueta) {
        this(etiqueta, false);
    }

    EstadoEntrega(String etiqueta, boolean terminal) {
        this.etiqueta = etiqueta;
        this.terminal = terminal;
    }

    /** Texto para mostrar en la UI. Aqui si van las tildes. */
    public String etiqueta() {
        return etiqueta;
    }

    /** Un estado terminal no admite ninguna transicion de salida. */
    public boolean esTerminal() {
        return terminal;
    }
}
