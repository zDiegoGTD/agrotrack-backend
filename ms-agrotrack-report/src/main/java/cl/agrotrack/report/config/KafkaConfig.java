package cl.agrotrack.report.config;

import cl.agrotrack.report.aplicacion.Envelope;
import cl.agrotrack.report.aplicacion.MensajeInvalidoException;
import cl.agrotrack.report.aplicacion.ResumenTimeline;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serializacion y politica de errores del cliente Kafka. Lo demas (brokers,
 * grupo, acks, idempotencia...) esta en application.yml.
 *
 * <p><b>Serializacion:</b> JSON con el ObjectMapper de Spring, el mismo de la
 * API REST, para que las fechas viajen en ISO-8601 como dice el contrato
 * (docs/02). Sin cabecera de tipo Java: cada servicio lee el JSON con su
 * propio record, sin acoplarse a las clases del emisor.
 *
 * <p><b>Errores:</b> el valor se deserializa dentro de un
 * {@link ErrorHandlingDeserializer}: un cuerpo ilegible no revienta el poll
 * en bucle, llega como error al handler. Un mensaje ilegible o invalido para
 * el negocio va directo a {@code <topico>.DLT}; un fallo transitorio (la base
 * caida) se reintenta 3 veces con 1 s entre intentos y despues va a la DLT.
 */
@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    /**
     * Report lee dos topicos con dos formas: deliveries.events trae un
     * {@link Envelope} y audit.timeline un {@link ResumenTimeline}. El tipo se
     * elige por el nombre del topico, no por cabeceras del emisor.
     */
    @Bean
    @SuppressWarnings({"unchecked", "rawtypes"})
    DefaultKafkaConsumerFactoryCustomizer deserializadorDeEventos(ObjectMapper json,
                                                                  @Value("${agrotrack.kafka.topico-timeline}") String timeline) {
        JavaType envelope = json.constructType(Envelope.class);
        JavaType resumen = json.constructType(ResumenTimeline.class);
        return factory -> ((DefaultKafkaConsumerFactory) factory).setValueDeserializerSupplier(() -> {
            JsonDeserializer<Object> porTopico = new JsonDeserializer<>(json);
            porTopico.setUseTypeHeaders(false);
            porTopico.setTypeResolver((topico, datos, cabeceras) -> timeline.equals(topico) ? resumen : envelope);
            return new ErrorHandlingDeserializer<>(porTopico);
        });
    }

    @Bean
    @SuppressWarnings("unchecked")
    DefaultKafkaProducerFactoryCustomizer serializadorJson(ObjectMapper json) {
        return factory -> ((DefaultKafkaProducerFactory<Object, Object>) factory).setValueSerializerSupplier(() -> {
            JsonSerializer<Object> s = new JsonSerializer<>(json);
            s.setAddTypeInfo(false);
            return s;
        });
    }

    @Bean
    CommonErrorHandler errorHandler(KafkaTemplate<Object, Object> kafka) {
        // Un registro que no se pudo deserializar se republica con sus bytes
        // originales (no hay objeto): necesita un productor de byte[], con la
        // misma conexion y propiedades que el productor principal.
        KafkaTemplate<String, byte[]> crudo = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
                kafka.getProducerFactory().getConfigurationProperties(), new StringSerializer(), new ByteArraySerializer()));
        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, crudo);
        templates.put(Object.class, kafka);

        // Destino explicito: el enunciado nombra los DLT como "<topico>.DLT";
        // el sufijo por defecto de Spring Kafka 3 es "-dlt" y no coincidiria
        // con el topico que declara ms-agrotrack-kafka-admin.
        DeadLetterPublishingRecoverer aDlt = new DeadLetterPublishingRecoverer(templates,
                (registro, ex) -> new TopicPartition(registro.topic() + ".DLT", registro.partition()));
        DefaultErrorHandler handler = new DefaultErrorHandler(
                (registro, ex) -> {
                    log.error("[dlt] {}@{} de {} (particion {}) -> DLT. Causa: {}",
                            registro.key(), registro.offset(), registro.topic(), registro.partition(), ex.getMessage());
                    aDlt.accept(registro, ex);
                },
                new FixedBackOff(1_000L, 2L)); // 1 intento + 2 reintentos = 3
        // DeserializationException ya es no reintentable por defecto; el JSON
        // bien formado pero incompleto tampoco tiene arreglo reintentando.
        handler.addNotRetryableExceptions(MensajeInvalidoException.class);
        return handler;
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
