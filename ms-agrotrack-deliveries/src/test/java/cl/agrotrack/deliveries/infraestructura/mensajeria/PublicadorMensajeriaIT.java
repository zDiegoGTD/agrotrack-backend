package cl.agrotrack.deliveries.infraestructura.mensajeria;

import cl.agrotrack.deliveries.dominio.Actor;
import cl.agrotrack.deliveries.dominio.Efecto;
import cl.agrotrack.deliveries.dominio.EstadoEntrega;
import cl.agrotrack.deliveries.dominio.Rol;
import cl.agrotrack.deliveries.infraestructura.persistencia.Entrega;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kafka y RabbitMQ reales en Docker, sin contexto Spring: se arma el
 * publicador a mano y se mira que llega a cada broker.
 */
class PublicadorMensajeriaIT {

    // Misma imagen que infra/kafka/compose.yml (la de AWS). KRaft, sin Zookeeper.
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))
            .withKraft();
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management");
    static final Instant AHORA = Instant.parse("2026-09-07T12:00:00Z");
    static final Actor OPERADOR = new Actor("op-1", "Jefa de acopio", Rol.OPERADOR);

    static KafkaTemplate<String, String> kafka;
    static CachingConnectionFactory rabbitCf;
    static RabbitTemplate rabbit;
    static PublicadorMensajeria publicador;
    static ObjectMapper json;

    @BeforeAll
    static void arrancar() {
        KAFKA.start();
        RABBIT.start();

        kafka = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)));

        rabbitCf = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        rabbitCf.setUsername(RABBIT.getAdminUsername());
        rabbitCf.setPassword(RABBIT.getAdminPassword());
        rabbit = new RabbitTemplate(rabbitCf);

        // Misma topologia que declara ms-agrotrack-mq-admin (solo lo que se usa aqui)
        RabbitAdmin admin = new RabbitAdmin(rabbitCf);
        DirectExchange direct = new DirectExchange("cmd.direct", true, false);
        admin.declareExchange(direct);
        for (String[] f : new String[][]{{"q.cmd.email", "email.send"}, {"q.cmd.receipt", "receipt.ticket"}, {"q.cmd.voucher", "voucher.gen"}}) {
            Queue q = new Queue(f[0], true);
            admin.declareQueue(q);
            Binding b = BindingBuilder.bind(q).to(direct).with(f[1]);
            admin.declareBinding(b);
        }

        json = new ObjectMapper().findAndRegisterModules().registerModule(new JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        publicador = new PublicadorMensajeria(kafka, rabbit, json,
                new Envelopes(Clock.fixed(AHORA, ZoneOffset.UTC)), "deliveries.events", "cmd.direct");
    }

    @AfterAll
    static void parar() {
        rabbitCf.destroy();
        KAFKA.stop();
        RABBIT.stop();
    }

    private static Entrega entrega() {
        return new Entrega("DEL-2026-000777", "prod-1", 7L, 3L, new BigDecimal("100"));
    }

    @Test
    @DisplayName("T2: un hecho delivery.received en Kafka (clave = codigo) y dos comandos en Rabbit, con envelope completo")
    void recibirPublicaHechoYComandos() throws Exception {
        Entrega e = entrega();
        e.transicionar(EstadoEntrega.RECIBIDA, new BigDecimal("98"), null, AHORA);

        publicador.entregaTransicionada(e, EstadoEntrega.REGISTRADA,
                Set.of(Efecto.DESCONTAR_CAPACIDAD, Efecto.NOTIFICAR_PRODUCTOR, Efecto.EMITIR_TICKET_BODEGA), OPERADOR);

        // --- Kafka: el hecho ---
        List<ConsumerRecord<String, String>> registros = leerKafka("deliveries.events", 1);
        assertThat(registros).hasSize(1);
        ConsumerRecord<String, String> r = registros.get(0);
        assertThat(r.key()).isEqualTo("DEL-2026-000777");
        JsonNode hecho = json.readTree(r.value());
        assertThat(hecho.get("type").asText()).isEqualTo("delivery.received");
        assertThat(hecho.get("specVersion").asText()).isEqualTo("1.0");
        assertThat(hecho.get("source").asText()).isEqualTo("ms-agrotrack-deliveries");
        assertThat(hecho.get("subject").asText()).isEqualTo("DEL-2026-000777");
        assertThat(hecho.get("eventId").asText()).matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        assertThat(hecho.get("traceId").asText()).hasSize(32);
        assertThat(hecho.get("correlationId").asText()).isEqualTo(Envelopes.correlacionDe("DEL-2026-000777"));
        assertThat(hecho.get("occurredAt").asText()).isEqualTo("2026-09-07T12:00:00Z");
        assertThat(hecho.get("actor").get("userId").asText()).isEqualTo("op-1");
        assertThat(hecho.get("actor").get("role").asText()).isEqualTo("OPERADOR");
        assertThat(hecho.get("data").get("estadoAnterior").asText()).isEqualTo("REGISTRADA");
        assertThat(hecho.get("data").get("estado").asText()).isEqualTo("RECIBIDA");
        assertThat(hecho.get("data").get("pesoRecibido").decimalValue()).isEqualByComparingTo("98");

        // --- Rabbit: los comandos. DESCONTAR_CAPACIDAD fue sincrono: no genera comando ---
        JsonNode email = json.readTree(cuerpo(rabbit.receive("q.cmd.email", 5_000)));
        assertThat(email.get("type").asText()).isEqualTo("email.send");
        assertThat(email.get("data").get("plantilla").asText()).isEqualTo("entrega-recibida");
        assertThat(email.get("correlationId").asText()).isEqualTo(hecho.get("correlationId").asText());
        assertThat(email.get("traceId").asText()).isEqualTo(hecho.get("traceId").asText());

        Message ticket = rabbit.receive("q.cmd.receipt", 5_000);
        JsonNode receipt = json.readTree(cuerpo(ticket));
        assertThat(receipt.get("type").asText()).isEqualTo("receipt.ticket");
        assertThat(ticket.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(ticket.getMessageProperties().getMessageId()).isEqualTo(receipt.get("eventId").asText());

        assertThat(rabbit.receive("q.cmd.voucher", 500)).as("recibir no genera guia").isNull();
    }

    @Test
    @DisplayName("T5: despachar genera la guia (voucher.gen) ademas del email")
    void despacharGeneraGuia() throws Exception {
        Entrega e = entrega();
        e.transicionar(EstadoEntrega.DESPACHADA, null, null, AHORA);

        publicador.entregaTransicionada(e, EstadoEntrega.EN_DESPACHO,
                Set.of(Efecto.GENERAR_GUIA_DESPACHO, Efecto.NOTIFICAR_PRODUCTOR), OPERADOR);

        JsonNode voucher = json.readTree(cuerpo(rabbit.receive("q.cmd.voucher", 5_000)));
        assertThat(voucher.get("type").asText()).isEqualTo("voucher.gen");
        assertThat(voucher.get("data").get("plantilla").asText()).isEqualTo("guia-despacho");
        assertThat(json.readTree(cuerpo(rabbit.receive("q.cmd.email", 5_000))).get("data").get("plantilla").asText())
                .isEqualTo("entrega-despachada");
    }

    @Test
    @DisplayName("T1: registrar solo publica el hecho; ningun comando")
    void registrarSoloHecho() {
        publicador.entregaRegistrada(entrega(), new Actor("prod-1", "Productor", Rol.CLIENTE));

        List<ConsumerRecord<String, String>> registros = leerKafka("deliveries.events", 1);
        assertThat(registros).anySatisfy(r -> assertThat(r.value()).contains("\"type\":\"delivery.registered\""));
        assertThat(rabbit.receive("q.cmd.email", 500)).isNull();
    }

    // ---- helpers ----

    private static String cuerpo(Message m) {
        assertThat(m).isNotNull();
        return new String(m.getBody(), StandardCharsets.UTF_8);
    }

    private static List<ConsumerRecord<String, String>> leerKafka(String topico, int minimo) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + System.nanoTime());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> todos = new ArrayList<>();
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
            c.subscribe(List.of(topico));
            long limite = System.currentTimeMillis() + 10_000;
            while (todos.size() < minimo && System.currentTimeMillis() < limite) {
                ConsumerRecords<String, String> lote = c.poll(Duration.ofMillis(500));
                lote.forEach(todos::add);
            }
        }
        // Solo los de este test: la particion acumula los de tests anteriores
        return todos.stream().skip(Math.max(0, todos.size() - minimo)).toList();
    }
}
