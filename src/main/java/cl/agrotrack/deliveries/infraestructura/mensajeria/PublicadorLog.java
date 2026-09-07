package cl.agrotrack.deliveries.infraestructura.mensajeria;

import cl.agrotrack.deliveries.aplicacion.PublicadorEventos;
import cl.agrotrack.deliveries.dominio.Actor;
import cl.agrotrack.deliveries.dominio.Efecto;
import cl.agrotrack.deliveries.dominio.EstadoEntrega;
import cl.agrotrack.deliveries.infraestructura.persistencia.Entrega;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * Publicador de respaldo: escribe en el log lo que se publicaria.
 *
 * <p>Se registra solo si no hay otro {@link PublicadorEventos} en el
 * contexto. Cuando entren Kafka y RabbitMQ (hitos B y C) ese bean lo
 * reemplaza sin tocar el servicio.
 */
@Configuration
public class PublicadorLog {

    private static final Logger log = LoggerFactory.getLogger(PublicadorLog.class);

    @Bean
    @ConditionalOnMissingBean(PublicadorEventos.class)
    PublicadorEventos publicadorEnLog() {
        return new PublicadorEventos() {
            @Override
            public void entregaRegistrada(Entrega e, Actor actor) {
                log.info("[evento] delivery.registered {} por {}", e.getCodigo(), actor.userId());
            }

            @Override
            public void entregaTransicionada(Entrega e, EstadoEntrega anterior, Set<Efecto> efectos, Actor actor) {
                log.info("[evento] {} {} -> {} efectos={} por {}",
                        e.getCodigo(), anterior, e.getEstado(), efectos, actor.userId());
            }
        };
    }
}
