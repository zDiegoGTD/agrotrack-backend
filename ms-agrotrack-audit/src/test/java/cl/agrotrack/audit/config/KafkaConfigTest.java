package cl.agrotrack.audit.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaConfigTest {

    @Test
    @DisplayName("El productor de bytes de la DLT usa la conexion del principal con su propio client.id (-dlt)")
    void productorCrudoConClientIdPropio() {
        DefaultKafkaProducerFactory<String, byte[]> crudo = KafkaConfig.productorCrudo(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker:9092",
                ProducerConfig.CLIENT_ID_CONFIG, "ms-agrotrack-audit"));

        assertThat(crudo.getConfigurationProperties())
                .containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker:9092")
                .containsEntry(ProducerConfig.CLIENT_ID_CONFIG, "ms-agrotrack-audit-dlt");
    }
}
