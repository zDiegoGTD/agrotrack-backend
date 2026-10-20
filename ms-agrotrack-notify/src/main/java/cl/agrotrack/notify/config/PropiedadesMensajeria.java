package cl.agrotrack.notify.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Politica de consumo, centralizada en application.yml
 * ({@code agrotrack.notify.reintentos}).
 *
 * @param esperas espera antes de cada reintento; su largo + 1 es el total de
 *                intentos. Con {@code [1s, 4s]} se intenta 3 veces.
 */
@ConfigurationProperties(prefix = "agrotrack.notify.reintentos")
public record PropiedadesMensajeria(List<Duration> esperas) {

    public PropiedadesMensajeria {
        esperas = esperas == null || esperas.isEmpty()
                ? List.of(Duration.ofSeconds(1), Duration.ofSeconds(4))
                : List.copyOf(esperas);
    }

    public int maxIntentos() {
        return esperas.size() + 1;
    }

    /** Espera antes del intento {@code intento + 1} (intento empieza en 1). */
    public Duration esperaTras(int intento) {
        return esperas.get(Math.min(intento - 1, esperas.size() - 1));
    }
}
