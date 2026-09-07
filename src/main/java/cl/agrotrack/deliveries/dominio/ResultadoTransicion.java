package cl.agrotrack.deliveries.dominio;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Respuesta de la maquina de estados: si la transicion procede y, en ese
 * caso, que hay que hacer a continuacion.
 *
 * @param permitida si la transicion es valida
 * @param motivo    por que no lo es; {@code null} cuando si lo es
 * @param efectos   consecuencias a ejecutar; vacio si la transicion se rechazo
 */
public record ResultadoTransicion(boolean permitida, MotivoRechazo motivo, Set<Efecto> efectos) {

    public ResultadoTransicion {
        efectos = efectos == null || efectos.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(efectos));
    }

    static ResultadoTransicion permitir(Efecto... efectos) {
        return new ResultadoTransicion(true, null,
                efectos.length == 0 ? Set.of() : EnumSet.of(efectos[0], efectos));
    }

    static ResultadoTransicion rechazar(MotivoRechazo motivo) {
        return new ResultadoTransicion(false, motivo, Set.of());
    }
}
