package cl.agrotrack.report.infraestructura.mensajeria;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Postgres + Kafka reales: un evento en el topico termina sumado en las tablas de KPI. */
@SpringBootTest
@Testcontainers
class KpiListenerIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("agro_report").withUsername("agro_report").withPassword("agro_report");

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1")).withKraft();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void crearTopicos() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(
                    new NewTopic("deliveries.events", 3, (short) 1),
                    new NewTopic("deliveries.events.DLT", 3, (short) 1))).all().get();
        }
    }

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("delivery.received por Kafka -> fila en ciclo_entrega y contador de la hora")
    void eventoSeAplica() {
        String cuerpo = """
                {"specVersion":"1.0","type":"delivery.received","eventId":"%s","occurredAt":"%s",
                 "traceId":"t","correlationId":"c","source":"ms-agrotrack-deliveries","subject":"DEL-K-9",
                 "actor":{"userId":"op-1","nombre":"Jefa","role":"OPERADOR"},
                 "data":{"codigo":"DEL-K-9","bodegaId":4,"productoId":1,"productorId":"p","cantidad":100,"pesoRecibido":90,"estado":"RECIBIDA"}}
                """.formatted(UUID.randomUUID(), Instant.now());
        kafka.send("deliveries.events", "DEL-K-9", cuerpo);

        await().atMost(Duration.ofSeconds(30)).until(() ->
                jdbc.queryForObject("SELECT COUNT(*) FROM ciclo_entrega WHERE entrega_codigo = 'DEL-K-9'", Integer.class) == 1);
        Integer recibidas = jdbc.queryForObject("SELECT SUM(recibidas) FROM kpi_entregas_hora WHERE bodega_id = 4", Integer.class);
        assertThat(recibidas).isEqualTo(1);
    }
}
