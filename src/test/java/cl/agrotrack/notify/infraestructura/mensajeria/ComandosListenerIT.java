package cl.agrotrack.notify.infraestructura.mensajeria;

import cl.agrotrack.notify.aplicacion.Envelope;
import cl.agrotrack.notify.aplicacion.TicketService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * RabbitMQ real. La topologia la declara aqui una TestConfiguration con
 * la misma forma que ms-agrotrack-mq-admin (notify no la declara: no es
 * su dueno).
 */
@SpringBootTest
@Testcontainers
class ComandosListenerIT {

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management");

    @TempDir
    static Path salida;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("agrotrack.notify.salida-dir", () -> salida.toString());
    }

    @TestConfiguration
    static class Topologia {
        @Bean
        Declarables topologiaDePrueba() {
            DirectExchange direct = new DirectExchange("cmd.direct", true, false);
            DirectExchange dlx = new DirectExchange("cmd.dead.dlx", true, false);
            List<Declarable> d = new ArrayList<>(List.of(direct, dlx));
            for (String[] f : new String[][]{{"q.cmd.email", "email.send"}, {"q.cmd.receipt", "receipt.ticket"}, {"q.cmd.voucher", "voucher.gen"}}) {
                Queue dlq = QueueBuilder.durable(f[0] + ".dlq").build();
                Queue q = QueueBuilder.durable(f[0]).deadLetterExchange("cmd.dead.dlx").deadLetterRoutingKey(f[0] + ".dlq").build();
                d.add(dlq);
                d.add(q);
                d.add(BindingBuilder.bind(q).to(direct).with(f[1]));
                d.add(BindingBuilder.bind(dlq).to(dlx).with(f[0] + ".dlq"));
            }
            return new Declarables(d);
        }
    }

    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin admin;
    @Autowired ObjectMapper json;
    @Autowired MeterRegistry registry;
    @MockitoSpyBean TicketService tickets;

    private Envelope comando(String eventId, String codigo) {
        return new Envelope("1.0", "receipt.ticket", eventId, Instant.parse("2026-09-07T12:00:00Z"),
                "trace-1", "corr-1", "ms-agrotrack-deliveries", codigo,
                new Envelope.Actor("op-1", "Jefa", "OPERADOR"),
                Map.of("plantilla", "ticket-recepcion", "productorId", "prod-1", "cantidad", 100, "pesoRecibido", 98));
    }

    private void enviar(String routingKey, String cuerpo) {
        Message m = MessageBuilder.withBody(cuerpo.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON).build();
        rabbit.send("cmd.direct", routingKey, m);
    }

    @Test
    @DisplayName("Un ticket llega, se escribe el archivo y la cola queda vacia (ACK)")
    void procesaYConfirma() throws Exception {
        admin.purgeQueue("q.cmd.receipt.dlq"); // otros tests dejan mensajes ahi a proposito
        enviar("receipt.ticket", json.writeValueAsString(comando(UUID.randomUUID().toString(), "DEL-IT-000001")));

        Path esperado = salida.resolve("tickets").resolve("DEL-IT-000001.txt");
        await().atMost(Duration.ofSeconds(10)).until(() -> Files.exists(esperado));
        await().atMost(Duration.ofSeconds(5)).until(() -> admin.getQueueInfo("q.cmd.receipt").getMessageCount() == 0);
        assertThat(admin.getQueueInfo("q.cmd.receipt.dlq").getMessageCount()).isZero();
    }

    @Test
    @DisplayName("El mismo eventId dos veces se ejecuta una sola vez (idempotencia)")
    void duplicadoSeDescarta() throws Exception {
        String eventId = UUID.randomUUID().toString();
        String cuerpo = json.writeValueAsString(comando(eventId, "DEL-IT-000002"));
        enviar("receipt.ticket", cuerpo);
        enviar("receipt.ticket", cuerpo);

        await().atMost(Duration.ofSeconds(10))
                .until(() -> admin.getQueueInfo("q.cmd.receipt").getMessageCount() == 0);
        Thread.sleep(500); // margen para que el segundo se procese (y se descarte)
        verify(tickets, times(1)).emitir(any());
    }

    @Test
    @DisplayName("JSON invalido: va a la DLQ sin reintentos y se cuenta en la metrica")
    void invalidoALaDlq() {
        double antes = registry.get("agrotrack.notify.dlq.total").counter().count();
        enviar("email.send", "esto no es json {");

        await().atMost(Duration.ofSeconds(10))
                .until(() -> admin.getQueueInfo("q.cmd.email.dlq").getMessageCount() == 1);
        assertThat(registry.get("agrotrack.notify.dlq.total").counter().count()).isEqualTo(antes + 1);

        Message enDlq = rabbit.receive("q.cmd.email.dlq", 2_000);
        assertThat(enDlq).isNotNull();
        assertThat(enDlq.getMessageProperties().getXDeathHeader().get(0).get("reason")).isEqualTo("rejected");
    }

    @Test
    @DisplayName("Fallo al ejecutar: se reintenta 3 veces y recien entonces va a la DLQ")
    void falloTransitorioReintenta() throws Exception {
        // Un codigo con caracteres ilegales para nombre de archivo hace fallar
        // la escritura del ticket en tiempo de ejecucion (no es JSON invalido,
        // asi que SI pasa por los reintentos).
        enviar("receipt.ticket", json.writeValueAsString(comando(UUID.randomUUID().toString(), "DEL/IT:000003?")));

        await().atMost(Duration.ofSeconds(30))
                .until(() -> admin.getQueueInfo("q.cmd.receipt.dlq").getMessageCount() >= 1);
        // 3 intentos = 3 llamadas al servicio antes de rendirse
        verify(tickets, times(3)).emitir(any());
    }
}
