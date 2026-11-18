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
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import java.nio.charset.StandardCharsets;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;
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

    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaListenerEndpointRegistry listeners;

    @Test
    @DisplayName("delivery.received por Kafka -> fila en ciclo_entrega y contador de la hora")
    void eventoSeAplica() {
        String cuerpo = """
                {"specVersion":"1.0","type":"delivery.received","eventId":"%s","occurredAt":"%s",
                 "traceId":"t","correlationId":"c","source":"ms-agrotrack-deliveries","subject":"DEL-K-9",
                 "actor":{"userId":"op-1","nombre":"Jefa","role":"OPERADOR"},
                 "data":{"codigo":"DEL-K-9","bodegaId":4,"productoId":1,"productorId":"p","cantidad":100,"pesoRecibido":90,"estado":"RECIBIDA"}}
                """.formatted(UUID.randomUUID(), Instant.now());
        enviarCrudo("deliveries.events", "DEL-K-9", cuerpo);

        await().atMost(Duration.ofSeconds(30)).until(() ->
                jdbc.queryForObject("SELECT COUNT(*) FROM ciclo_entrega WHERE entrega_codigo = 'DEL-K-9'", Integer.class) == 1);
        Integer recibidas = jdbc.queryForObject("SELECT SUM(recibidas) FROM kpi_entregas_hora WHERE bodega_id = 4", Integer.class);
        assertThat(recibidas).isEqualTo(1);
    }

    @Test
    @DisplayName("Un cuerpo ilegible va a la DLT con sus bytes originales y el consumidor sigue con el siguiente")
    void ilegibleAlDlt_yElSiguienteSeAplica() {
        enviarCrudo("deliveries.events", "roto", "{roto");
        String cuerpo = """
                {"specVersion":"1.0","type":"delivery.received","eventId":"%s","occurredAt":"%s",
                 "source":"ms-agrotrack-deliveries","subject":"DEL-K-10",
                 "data":{"bodegaId":5,"productoId":1,"pesoRecibido":10,"estado":"RECIBIDA"}}
                """.formatted(UUID.randomUUID(), Instant.now());
        enviarCrudo("deliveries.events", "DEL-K-10", cuerpo);

        ConsumerRecord<String, String> dlt = esperar("deliveries.events.DLT", "roto");
        assertThat(dlt.value()).isEqualTo("{roto");
        String causas = new String(dlt.headers().lastHeader("kafka_dlt-exception-fqcn").value(), StandardCharsets.UTF_8)
                + new String(dlt.headers().lastHeader("kafka_dlt-exception-cause-fqcn").value(), StandardCharsets.UTF_8);
        assertThat(causas).contains("DeserializationException");
        await().atMost(Duration.ofSeconds(30)).until(() ->
                jdbc.queryForObject("SELECT COUNT(*) FROM ciclo_entrega WHERE entrega_codigo = 'DEL-K-10'", Integer.class) == 1);
    }

    @Test
    @DisplayName("El grupo report-consumer corre 3 hilos, uno por particion de deliveries.events")
    void concurrenciaIgualAParticiones() {
        ConcurrentMessageListenerContainer<?, ?> contenedor = listeners.getListenerContainers().stream()
                .filter(c -> "report-consumer".equals(c.getGroupId()))
                .map(c -> (ConcurrentMessageListenerContainer<?, ?>) c)
                .findFirst().orElseThrow();

        assertThat(contenedor.getConcurrency()).isEqualTo(3);
        await().atMost(Duration.ofSeconds(30)).until(() -> contenedor.getContainers().stream()
                .allMatch(h -> h.getAssignedPartitions() != null && h.getAssignedPartitions().size() == 1));
        assertThat(contenedor.getContainers().stream()
                .flatMap(h -> h.getAssignedPartitions().stream()).distinct().count()).isEqualTo(3);
    }

    private static void enviarCrudo(String topico, String clave, String valor) {
        try (KafkaProducer<String, String> p = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            p.send(new ProducerRecord<>(topico, clave, valor));
        }
    }

    private static ConsumerRecord<String, String> esperar(String topico, String clave) {
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + System.nanoTime(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            c.subscribe(List.of(topico));
            long limite = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < limite) {
                for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(500))) {
                    if (clave.equals(r.key())) {
                        return r;
                    }
                }
            }
        }
        throw new AssertionError("no llego a " + topico + " un registro con clave " + clave);
    }
}
