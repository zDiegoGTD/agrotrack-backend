package cl.agrotrack.audit.infraestructura.mensajeria;

import cl.agrotrack.audit.aplicacion.AuditService;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Postgres + Kafka reales: el evento entra por el topico y sale por la base (y por audit.timeline). */
@SpringBootTest
@Testcontainers
class TimelineListenerIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("agro_audit").withUsername("agro_audit").withPassword("agro_audit");

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1")).withKraft();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired AuditService audit;

    /**
     * Los topicos se crean antes, como haria ms-agrotrack-kafka-admin. Si se
     * dejara la auto-creacion al primer mensaje, un consumidor suscrito a un
     * topico que todavia no existe tarda metadata.max.age.ms (5 min) en verlo.
     */
    @BeforeAll
    static void crearTopicos() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(
                    new NewTopic("deliveries.events", 3, (short) 1),
                    new NewTopic("audit.timeline", 3, (short) 1),
                    new NewTopic("deliveries.events.DLT", 3, (short) 1))).all().get();
        }
    }

    private static String evento(String eventId, String codigo) {
        return """
                {"specVersion":"1.0","type":"delivery.received","eventId":"%s","occurredAt":"2026-09-07T12:00:00Z",
                 "traceId":"t1","correlationId":"c1","source":"ms-agrotrack-deliveries","subject":"%s",
                 "actor":{"userId":"op-1","nombre":"Jefa","role":"OPERADOR"},
                 "data":{"codigo":"%s","estado":"RECIBIDA"}}
                """.formatted(eventId, codigo, codigo);
    }

    @Test
    @DisplayName("Un evento en deliveries.events termina en el timeline y su resumen en audit.timeline")
    void eventoTerminaEnTimeline() {
        String id = UUID.randomUUID().toString();
        kafka.send("deliveries.events", "DEL-K-1", evento(id, "DEL-K-1"));

        await().atMost(Duration.ofSeconds(30)).until(() -> !audit.timelineDe("DEL-K-1").isEmpty());
        assertThat(audit.timelineDe("DEL-K-1").get(0).getEventId()).isEqualTo(id);

        List<ConsumerRecord<String, String>> resumenes = leer("audit.timeline", 1);
        assertThat(resumenes).anySatisfy(r -> {
            assertThat(r.key()).isEqualTo("DEL-K-1");
            assertThat(r.value()).contains("\"ultimoEvento\":\"delivery.received\"").contains("\"estado\":\"RECIBIDA\"");
        });
    }

    @Test
    @DisplayName("Un mensaje invalido va a deliveries.events.DLT con la causa en cabeceras, sin reintentos")
    void invalidoAlDlt() {
        kafka.send("deliveries.events", "basura", "esto no es json {");

        List<ConsumerRecord<String, String>> dlt = leer("deliveries.events.DLT", 1);
        assertThat(dlt).hasSizeGreaterThanOrEqualTo(1);
        ConsumerRecord<String, String> r = dlt.get(dlt.size() - 1);
        assertThat(r.value()).isEqualTo("esto no es json {");
        // Cabeceras que deja el DeadLetterPublishingRecoverer: la excepcion del
        // listener (envolvente) y su causa real, mas topico/particion/offset de origen.
        String causa = new String(r.headers().lastHeader("kafka_dlt-exception-cause-fqcn").value(), StandardCharsets.UTF_8);
        assertThat(causa).contains("MensajeInvalidoException");
        assertThat(new String(r.headers().lastHeader("kafka_dlt-original-topic").value(), StandardCharsets.UTF_8))
                .isEqualTo("deliveries.events");
    }

    private static List<ConsumerRecord<String, String>> leer(String topico, int minimo) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + System.nanoTime());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, "1000");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> todos = new ArrayList<>();
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
            c.subscribe(List.of(topico));
            long limite = System.currentTimeMillis() + 30_000;
            while (todos.size() < minimo && System.currentTimeMillis() < limite) {
                c.poll(Duration.ofMillis(500)).forEach(todos::add);
            }
        }
        return todos;
    }
}
