package cl.agrotrack.audit.config;

import cl.agrotrack.audit.aplicacion.MensajeInvalidoException;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Clock;

/**
 * Politica de consumo: 3 intentos (1 s entre ellos) y despues el registro
 * va a {@code deliveries.events.DLT} con cabeceras que describen la
 * excepcion, el topico y el offset de origen. Un JSON invalido no se
 * reintenta: va directo.
 */
@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    @Bean
    CommonErrorHandler errorHandler(KafkaTemplate<String, String> kafka) {
        // Destino explicito: el enunciado nombra los DLT como "<topico>.DLT";
        // el sufijo por defecto de Spring Kafka 3 es "-dlt" y no coincidiria
        // con el topico que declara ms-agrotrack-kafka-admin.
        DeadLetterPublishingRecoverer aDlt = new DeadLetterPublishingRecoverer(kafka,
                (registro, ex) -> new TopicPartition(registro.topic() + ".DLT", registro.partition()));
        DefaultErrorHandler handler = new DefaultErrorHandler(
                (registro, ex) -> {
                    log.error("[dlt] {}@{} de {} -> DLT. Causa: {}",
                            registro.key(), registro.offset(), registro.topic(), ex.getMessage());
                    aDlt.accept(registro, ex);
                },
                new FixedBackOff(1_000L, 2L)); // 1 intento + 2 reintentos = 3
        handler.addNotRetryableExceptions(MensajeInvalidoException.class);
        return handler;
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
