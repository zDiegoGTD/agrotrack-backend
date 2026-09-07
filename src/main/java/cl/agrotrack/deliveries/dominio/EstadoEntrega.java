package cl.agrotrack.deliveries.dominio;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Estados del ciclo de vida de una entrega.
 *
 * <p>Sin tilde en EN_CLASIFICACION a proposito: la tilde vive solo en la
 * etiqueta que ve el usuario. Ver docs/00-decisiones.md (D3).
 */
public enum EstadoEntrega {

    REGISTRADA("Registrada"),
    RECIBIDA("Recibida"),
    EN_CLASIFICACION("En clasificación"),
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

    /**
     * Acepta lo que mande el cliente: con o sin tilde, en cualquier caja,
     * con espacios o guiones. "EN_CLASIFICACIÓN" (como lo escribe el
     * enunciado) y "en clasificacion" resuelven al mismo valor.
     *
     * @throws IllegalArgumentException si no corresponde a ningun estado
     */
    public static EstadoEntrega parse(String texto) {
        if (texto == null || texto.isBlank()) {
            throw new IllegalArgumentException("El estado es obligatorio");
        }
        String normalizado = Normalizer.normalize(texto.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[\\s\\-]+", "_");
        try {
            return valueOf(normalizado);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Estado desconocido: " + texto);
        }
    }
}
