package cl.agrotrack.mqadmin;

import cl.agrotrack.mqadmin.config.TopologiaRabbit;
import cl.agrotrack.mqadmin.config.TopologiaRabbit.Flujo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Un RabbitMQ real en Docker: al arrancar el servicio, la topologia tiene que existir. */
@SpringBootTest
@Testcontainers
class TopologiaIT {

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management");

    @Autowired AmqpAdmin admin;
    @Autowired RabbitTemplate rabbit;
    @Autowired ConnectionFactory connectionFactory;

    @Test
    @DisplayName("Las 3 colas principales y sus 3 DLQ existen, vacias")
    void seisColas() {
        for (Flujo f : TopologiaRabbit.FLUJOS) {
            for (String nombre : List.of(f.cola(), f.dlq())) {
                QueueInformation info = admin.getQueueInfo(nombre);
                assertThat(info).as("cola %s", nombre).isNotNull();
                assertThat(info.getMessageCount()).as("cola %s vacia", nombre).isZero();
            }
        }
    }

    @Test
    @DisplayName("Los tres exchanges existen: publicar contra ellos no falla")
    void exchanges() {
        // Declarar un exchange que ya existe con los mismos atributos es un no-op;
        // si no existiera, el broker lo crearia y esta prueba no probaria nada.
        // Por eso se prueba publicando: contra un exchange inexistente el canal se cierra.
        rabbit.convertAndSend(TopologiaRabbit.EXCHANGE_DIRECT, "email.send", "ping");
        rabbit.convertAndSend(TopologiaRabbit.EXCHANGE_TOPIC, "receipt.ticket.x", "ping");
        rabbit.convertAndSend(TopologiaRabbit.EXCHANGE_DLX, "q.cmd.voucher.dlq", "ping");

        assertThat(recibir("q.cmd.email")).isEqualTo("ping");
        assertThat(recibir("q.cmd.receipt")).isEqualTo("ping");
        assertThat(recibir("q.cmd.voucher.dlq")).isEqualTo("ping");
    }

    @Test
    @DisplayName("Binding direct: email.send cae en q.cmd.email; binding topic: email.cualquiera tambien")
    void bindingsEmail() {
        rabbit.convertAndSend(TopologiaRabbit.EXCHANGE_DIRECT, "email.send", "directo");
        rabbit.convertAndSend(TopologiaRabbit.EXCHANGE_TOPIC, "email.bienvenida", "por-topic");

        assertThat(recibir("q.cmd.email")).isEqualTo("directo");
        assertThat(recibir("q.cmd.email")).isEqualTo("por-topic");
    }

    @Test
    @DisplayName("Un mensaje rechazado sin requeue termina en la DLQ del mismo flujo, con x-death")
    void rechazoVaALaDlq() throws Exception {
        rabbit.convertAndSend(TopologiaRabbit.EXCHANGE_DIRECT, "voucher.gen", "voy-a-fallar");

        try (var conn = connectionFactory.createConnection(); var canal = conn.createChannel(false)) {
            var entregado = canal.basicGet("q.cmd.voucher", false);
            assertThat(entregado).isNotNull();
            canal.basicReject(entregado.getEnvelope().getDeliveryTag(), false);
        }

        Message enDlq = esperar("q.cmd.voucher.dlq");
        assertThat(new String(enDlq.getBody(), StandardCharsets.UTF_8)).isEqualTo("voy-a-fallar");
        assertThat(enDlq.getMessageProperties().getXDeathHeader()).isNotEmpty();
        assertThat(enDlq.getMessageProperties().getXDeathHeader().get(0).get("reason")).isEqualTo("rejected");
    }

    private String recibir(String cola) {
        Message m = esperar(cola);
        return new String(m.getBody(), StandardCharsets.UTF_8);
    }

    private Message esperar(String cola) {
        Message m = rabbit.receive(cola, 5_000);
        assertThat(m).as("mensaje en %s", cola).isNotNull();
        return m;
    }
}
