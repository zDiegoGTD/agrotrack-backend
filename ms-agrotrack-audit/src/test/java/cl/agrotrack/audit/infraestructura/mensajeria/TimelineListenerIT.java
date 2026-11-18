package cl.agrotrack.audit.infraestructura.mensajeria;

import cl.agrotrack.audit.aplicacion.AuditService;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
        enviarCrudo("deliveries.events", "DEL-K-1", evento(id, "DEL-K-1"));

        await().atMost(Duration.ofSeconds(30)).until(() -> !audit.timelineDe("DEL-K-1").isEmpty());
        assertThat(audit.timelineDe("DEL-K-1").get(0).getEventId()).isEqualTo(id);

        List<ConsumerRecord<String, String>> resumenes = leer("audit.timeline", 1);
        assertThat(resumenes).anySatisfy(r -> {
            assertThat(r.key()).isEqualTo("DEL-K-1");
            assertThat(r.value()).contains("\"ultimoEvento\":\"delivery.received\"").contains("\"estado\":\"RECIBIDA\"");
        });
    }

    @Test
    @DisplayName("Un cuerpo que no es JSON no se puede deserializar: va a la DLT tal cual y el consumidor sigue con el siguiente")
    void ilegibleAlDlt_yElSiguienteSeProcesa() {
        enviarCrudo("deliveries.events", "basura", "esto no es json {");
        String id = UUID.randomUUID().toString();
        enviarCrudo("deliveries.events", "DEL-K-2", evento(id, "DEL-K-2"));

        ConsumerRecord<String, String> r = esperar("deliveries.events.DLT", "basura");
        assertThat(r.value()).isEqualTo("esto no es json {");
        assertThat(cabecera(r, "kafka_dlt-exception-fqcn") + cabecera(r, "kafka_dlt-exception-cause-fqcn"))
                .contains("DeserializationException");
        assertThat(cabecera(r, "kafka_dlt-original-topic")).isEqualTo("deliveries.events");

        await().atMost(Duration.ofSeconds(30)).until(() -> !audit.timelineDe("DEL-K-2").isEmpty());
    }

    @Test
    @DisplayName("Un JSON valido sin eventId es invalido para el negocio: va a la DLT sin reintentos")
    void sinEventIdAlDlt() {
        enviarCrudo("deliveries.events", "DEL-K-3",
                "{\"type\":\"delivery.received\",\"subject\":\"DEL-K-3\",\"occurredAt\":\"2026-09-07T12:00:00Z\"}");

        ConsumerRecord<String, String> r = esperar("deliveries.events.DLT", "DEL-K-3");
        assertThat(cabecera(r, "kafka_dlt-exception-cause-fqcn")).contains("MensajeInvalidoException");
    }

    @Test
    @DisplayName("El resumen en audit.timeline es un ResumenTimeline JSON, sin cabecera de tipo Java")
    void resumenTipado() throws Exception {
        String id = UUID.randomUUID().toString();
        enviarCrudo("deliveries.events", "DEL-K-4", evento(id, "DEL-K-4"));

        ConsumerRecord<String, String> r = esperar("audit.timeline", "DEL-K-4");
        assertThat(r.headers().lastHeader("__TypeId__")).isNull();
        JsonNode resumen = new ObjectMapper().readTree(r.value());
        assertThat(resumen.get("codigo").asText()).isEqualTo("DEL-K-4");
        assertThat(resumen.get("eventId").asText()).isEqualTo(id);
        assertThat(resumen.get("actorRol").asText()).isEqualTo("OPERADOR");
        assertThat(resumen.get("ocurridoEn").asText()).isEqualTo("2026-09-07T12:00:00Z");
    }

    private static void enviarCrudo(String topico, String clave, String valor) {
        try (KafkaProducer<String, String> p = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            p.send(new ProducerRecord<>(topico, clave, valor));
        }
    }

    /** Lee el topico desde el inicio hasta que aparece un registro con esa clave (30 s maximo). */
    private static ConsumerRecord<String, String> esperar(String topico, String clave) {
        long limite = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < limite) {
            var con = leer(topico, 1).stream().filter(x -> clave.equals(x.key())).reduce((a, b) -> b);
            if (con.isPresent()) {
                return con.get();
            }
        }
        throw new AssertionError("no llego a " + topico + " un registro con clave " + clave);
    }

    private static String cabecera(ConsumerRecord<String, String> r, String nombre) {
        var h = r.headers().lastHeader(nombre);
        return h == null ? "" : new String(h.value(), StandardCharsets.UTF_8);
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
