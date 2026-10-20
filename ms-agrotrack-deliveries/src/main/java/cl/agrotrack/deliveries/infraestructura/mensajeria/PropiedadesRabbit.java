package cl.agrotrack.deliveries.infraestructura.mensajeria;

import cl.agrotrack.deliveries.dominio.Efecto;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Nombres de RabbitMQ que usa deliveries, centralizados en application.yml
 * ({@code agrotrack.rabbit}). Ningun nombre de exchange ni routing key queda
 * escrito en el codigo de publicacion.
 *
 * @param exchangeComandos exchange direct donde se publican los comandos
 * @param comandos         efecto asincrono del dominio -> routing key de su comando;
 *                         cada routing key coincide con un binding declarado por mq-admin
 */
@ConfigurationProperties(prefix = "agrotrack.rabbit")
public record PropiedadesRabbit(String exchangeComandos, Map<Efecto, String> comandos) {

    public PropiedadesRabbit {
        comandos = comandos == null ? Map.of() : Map.copyOf(comandos);
    }
}
