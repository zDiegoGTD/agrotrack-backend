package cl.agrotrack.notify.aplicacion;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Idempotencia de notify: eventIds ya procesados.
 *
 * <p>Notify no tiene base de datos (enunciado, seccion 5), asi que la tabla
 * PROCESSED_EVENTS de los otros consumidores se reemplaza por un conjunto
 * en memoria acotado. Limite conocido: al reiniciar se olvida, y un mensaje
 * reentregado justo despues del reinicio se procesaria dos veces (un email
 * repetido). Para tickets y guias el archivo se sobreescribe, sin dano.
 *
 * <p>Si esa ventana resulta inaceptable, la salida es darle a notify una
 * tabla propia; se decide con la pauta.
 */
@Component
public class ProcesadosStore {

    private static final int CAPACIDAD = 50_000;

    private final Map<String, Boolean> vistos = new LinkedHashMap<>(1024, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > CAPACIDAD;
        }
    };

    /** @return true si es la primera vez que se ve este eventId (y queda marcado). */
    public synchronized boolean marcarSiEsNuevo(String eventId) {
        return vistos.putIfAbsent(eventId, Boolean.TRUE) == null;
    }

    /** Si la ejecucion fallo, el evento no cuenta como procesado. */
    public synchronized void desmarcar(String eventId) {
        vistos.remove(eventId);
    }

    public synchronized int tamano() {
        return vistos.size();
    }
}
