package cl.agrotrack.users.dominio;

import java.util.Map;
import java.util.Set;

import static cl.agrotrack.users.dominio.EstadoUsuario.*;

/**
 * Reglas del ciclo de vida de una cuenta. Clase pura, igual que la maquina
 * de estados de la entrega (D6): sin Spring, sin JPA, sin red.
 */
public final class MaquinaEstadosUsuario {

    private static final Map<EstadoUsuario, Set<EstadoUsuario>> PERMITIDAS = Map.of(
            PENDIENTE, Set.of(ACTIVO, RECHAZADO),
            ACTIVO, Set.of(INACTIVO),
            INACTIVO, Set.of(ACTIVO),
            RECHAZADO, Set.of(ACTIVO));

    private MaquinaEstadosUsuario() {
    }

    /**
     * Si todos nacieran PENDIENTE nadie podria aprobar a nadie y el sistema
     * naceria bloqueado. Por eso, mientras no haya ningun ACTIVO, el primero
     * que entra con ADMIN en el token queda activo. Solo puede pasar una vez.
     */
    public static EstadoUsuario estadoInicial(boolean tokenTraeAdmin, boolean hayUsuariosActivos) {
        return tokenTraeAdmin && !hayUsuariosActivos ? ACTIVO : PENDIENTE;
    }

    /**
     * El orden importa: primero si la transicion existe, despues la
     * auto-desactivacion y al final el motivo, para que el mensaje apunte a
     * lo que de verdad impide el cambio.
     */
    public static void validarCambio(EstadoUsuario actual, EstadoUsuario destino, String motivo, boolean esElMismoUsuario) {
        if (!PERMITIDAS.get(actual).contains(destino)) {
            throw new CambioEstadoInvalidoException(MotivoRechazoCambio.TRANSICION_NO_PERMITIDA,
                    "No se puede pasar de " + actual + " a " + destino);
        }
        if (destino == INACTIVO && esElMismoUsuario) {
            // Si es el unico admin, dejaria el sistema sin quien apruebe.
            throw new CambioEstadoInvalidoException(MotivoRechazoCambio.AUTO_DESACTIVACION,
                    "No puedes desactivar tu propia cuenta");
        }
        if ((destino == RECHAZADO || destino == INACTIVO) && (motivo == null || motivo.isBlank())) {
            throw new CambioEstadoInvalidoException(MotivoRechazoCambio.MOTIVO_OBLIGATORIO,
                    "Indica el motivo para dejar la cuenta en " + destino);
        }
    }
}
