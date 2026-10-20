package cl.agrotrack.deliveries.infraestructura.mensajeria;

import cl.agrotrack.deliveries.aplicacion.PublicadorEventos;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Clock;

/**
 * Cablea el publicador real. {@code agrotrack.mensajeria.enabled=false}
 * lo apaga y deja el publicador de log (util en tests de persistencia y
 * para desarrollar sin brokers).
 */
@Configuration
@EnableConfigurationProperties(PropiedadesRabbit.class)
public class MensajeriaConfig {

    @Bean
    Envelopes envelopes(Clock clock) {
        return new Envelopes(clock);
    }

    @Bean
    @ConditionalOnProperty(name = "agrotrack.mensajeria.enabled", havingValue = "true", matchIfMissing = true)
    RegistroNoEntregados registroNoEntregados(MeterRegistry registry, RabbitTemplate rabbit) {
        RegistroNoEntregados registro = new RegistroNoEntregados(registry);
        registro.registrarEn(rabbit);
        return registro;
    }

    @Bean
    @ConditionalOnProperty(name = "agrotrack.mensajeria.enabled", havingValue = "true", matchIfMissing = true)
    PublicadorEventos publicadorMensajeria(KafkaTemplate<String, String> kafka,
                                           RabbitTemplate rabbit,
                                           ObjectMapper json,
                                           Envelopes envelopes,
                                           @Value("${agrotrack.kafka.topico-eventos}") String topico,
                                           PropiedadesRabbit rabbitProps) {
        return new PublicadorMensajeria(kafka, rabbit, json, envelopes, topico,
                rabbitProps.exchangeComandos(), rabbitProps.comandos());
    }
}
