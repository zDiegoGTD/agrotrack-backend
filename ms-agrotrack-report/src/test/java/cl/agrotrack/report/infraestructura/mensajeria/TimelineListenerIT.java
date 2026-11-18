package cl.agrotrack.report.infraestructura.mensajeria;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** El segundo topico: un resumen en audit.timeline termina como actividad por rol y ultimo estado. */
@SpringBootTest
@Testcontainers
class TimelineListenerIT {

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
                    new NewTopic("deliveries.events.DLT", 3, (short) 1),
                    new NewTopic("audit.timeline", 3, (short) 1),
                    new NewTopic("audit.timeline.DLT", 3, (short) 1))).all().get();
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaListenerEndpointRegistry listeners;

    @Test
    @DisplayName("Un ResumenTimeline publicado por audit se refleja en actividad_rol y ultimo_estado")
    void resumenSeReflejaEnActividad() {
        String resumen = """
                {"codigo":"DEL-TL-1","ultimoEvento":"delivery.received","estado":"RECIBIDA",
                 "actorId":"op-1","actorRol":"OPERADOR","ocurridoEn":"2026-02-01T09:20:00Z","eventId":"%s"}
                """.formatted(UUID.randomUUID());
        enviarCrudo("audit.timeline", "DEL-TL-1", resumen);

        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM ultimo_estado WHERE entrega_codigo = 'DEL-TL-1'", Integer.class) == 1);
        Integer eventos = jdbc.queryForObject(
                "SELECT eventos FROM actividad_rol WHERE hora = '2026-02-01T09:00:00Z' AND rol = 'OPERADOR'", Integer.class);
        assertThat(eventos).isEqualTo(1);
    }

    @Test
    @DisplayName("audit.timeline se lee con su propio consumer group (report-timeline), aparte de report-consumer")
    void grupoPropio() {
        assertThat(listeners.getListenerContainers()).extracting(c -> c.getGroupId())
                .contains("report-consumer", "report-timeline");
    }

    private static void enviarCrudo(String topico, String clave, String valor) {
        try (KafkaProducer<String, String> p = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            p.send(new ProducerRecord<>(topico, clave, valor));
        }
    }
}
