package cl.agrotrack.kafkaadmin;

import cl.agrotrack.kafkaadmin.config.TopologiaKafka;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaAdmin;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Un Kafka real (KRaft, 1 broker): al arrancar el servicio, los topicos tienen que existir con su config. */
@SpringBootTest(properties = "agrotrack.kafka.replicas=1")
@Testcontainers
class TopologiaKafkaIT {

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))
            .withKraft();

    @Autowired KafkaAdmin kafkaAdmin;

    @Test
    @DisplayName("Los tres topicos existen con 3 particiones, 1 replica y la politica/retencion del enunciado")
    void topicosDeclarados() throws Exception {
        List<String> nombres = List.of(TopologiaKafka.TOPICO_EVENTOS, TopologiaKafka.TOPICO_TIMELINE, TopologiaKafka.TOPICO_DLT);

        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            Map<String, TopicDescription> desc = admin.describeTopics(nombres).allTopicNames().get();
            Map<ConfigResource, Config> cfgs = admin.describeConfigs(
                    nombres.stream().map(n -> new ConfigResource(ConfigResource.Type.TOPIC, n)).toList()).all().get();

            for (String n : nombres) {
                assertThat(desc.get(n).partitions()).as("particiones de %s", n).hasSize(3);
                assertThat(desc.get(n).partitions().get(0).replicas()).as("replicas de %s", n).hasSize(1);
            }

            Config eventos = cfgs.get(new ConfigResource(ConfigResource.Type.TOPIC, TopologiaKafka.TOPICO_EVENTOS));
            assertThat(eventos.get(TopicConfig.CLEANUP_POLICY_CONFIG).value()).isEqualTo("delete");
            assertThat(eventos.get(TopicConfig.RETENTION_MS_CONFIG).value()).isEqualTo(String.valueOf(Duration.ofDays(7).toMillis()));
            assertThat(eventos.get(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG).value()).isEqualTo("1");

            Config timeline = cfgs.get(new ConfigResource(ConfigResource.Type.TOPIC, TopologiaKafka.TOPICO_TIMELINE));
            assertThat(timeline.get(TopicConfig.CLEANUP_POLICY_CONFIG).value()).isEqualTo("compact,delete");
            assertThat(timeline.get(TopicConfig.RETENTION_MS_CONFIG).value()).isEqualTo(String.valueOf(Duration.ofDays(30).toMillis()));

            Config dlt = cfgs.get(new ConfigResource(ConfigResource.Type.TOPIC, TopologiaKafka.TOPICO_DLT));
            assertThat(dlt.get(TopicConfig.RETENTION_MS_CONFIG).value()).isEqualTo(String.valueOf(Duration.ofDays(14).toMillis()));
        }
    }
}
