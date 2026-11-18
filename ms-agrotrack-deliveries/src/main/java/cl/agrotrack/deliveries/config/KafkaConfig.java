package cl.agrotrack.deliveries.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * Serializacion del valor de los hechos que van a Kafka. Lo demas del
 * cliente (brokers, acks, idempotencia...) esta en application.yml.
 *
 * <p>JSON con el ObjectMapper de Spring, el mismo de la API REST: las fechas
 * viajan en ISO-8601 como dice el contrato (docs/02). Sin cabecera de tipo
 * Java: audit y report leen el JSON con su propio record y no dependen de
 * las clases de este servicio.
 */
@Configuration
public class KafkaConfig {

    @Bean
    @SuppressWarnings({"unchecked", "rawtypes"})
    DefaultKafkaProducerFactoryCustomizer serializadorJson(ObjectMapper json) {
        return factory -> ((DefaultKafkaProducerFactory) factory).setValueSerializerSupplier(() ->
                new JsonSerializer<>(json).noTypeInfo());
    }
}
