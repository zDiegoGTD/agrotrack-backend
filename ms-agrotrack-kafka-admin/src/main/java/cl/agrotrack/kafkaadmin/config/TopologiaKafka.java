package cl.agrotrack.kafkaadmin.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Los topicos del enunciado (seccion 9), declarados en codigo.
 *
 * <p>KafkaAdmin los crea al arrancar si no existen. Si existen con otra
 * cantidad de particiones, Spring las aumenta (nunca reduce); la
 * configuracion (retencion, compactacion) se aplica en la creacion.
 *
 * <p>Las replicas vienen por propiedad: 1 en el PC (un broker), 3 en AWS.
 * Pedir 3 replicas a un cluster de 1 broker falla al crear el topico, y
 * como {@code spring.kafka.admin.fail-fast=true}, el servicio no arranca:
 * es preferible a arrancar con una topologia a medias.
 */
@Configuration
public class TopologiaKafka {

    public static final String TOPICO_EVENTOS = "deliveries.events";
    public static final String TOPICO_TIMELINE = "audit.timeline";
    public static final String TOPICO_DLT = "deliveries.events.DLT";

    /** Definicion declarativa, tambien la fuente para GET /api/kafka/topics. */
    public record Definicion(String nombre, int particiones, String politica, Duration retencion, String proposito) {
    }

    public static List<Definicion> definiciones(int particiones) {
        return List.of(
                new Definicion(TOPICO_EVENTOS, particiones, "delete", Duration.ofDays(7),
                        "Fuente de verdad de eventos de la entrega. Alimenta reporteria y auditoria."),
                new Definicion(TOPICO_TIMELINE, particiones, "compact,delete", Duration.ofDays(30),
                        "Historial quien / que / cuando / desde donde (compactado por entrega)."),
                new Definicion(TOPICO_DLT, particiones, "delete", Duration.ofDays(14),
                        "Mensajes que fallaron tras N reintentos, con metadatos de error."));
    }

    private final int replicas;
    private final int particiones;

    public TopologiaKafka(@Value("${agrotrack.kafka.replicas}") int replicas,
                          @Value("${agrotrack.kafka.particiones}") int particiones) {
        this.replicas = replicas;
        this.particiones = particiones;
    }

    @Bean
    public KafkaAdmin.NewTopics topicos() {
        // Con 3 replicas se exige que 2 confirmen la escritura (min.insync.replicas);
        // con 1 replica solo puede ser 1.
        String minIsr = String.valueOf(replicas >= 3 ? 2 : 1);

        NewTopic[] topicos = definiciones(particiones).stream()
                .map(d -> TopicBuilder.name(d.nombre())
                        .partitions(d.particiones())
                        .replicas(replicas)
                        .configs(Map.of(
                                TopicConfig.CLEANUP_POLICY_CONFIG, d.politica(),
                                TopicConfig.RETENTION_MS_CONFIG, String.valueOf(d.retencion().toMillis()),
                                TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, minIsr))
                        .build())
                .toArray(NewTopic[]::new);
        return new KafkaAdmin.NewTopics(topicos);
    }

    public int replicas() {
        return replicas;
    }

    public int particiones() {
        return particiones;
    }
}
