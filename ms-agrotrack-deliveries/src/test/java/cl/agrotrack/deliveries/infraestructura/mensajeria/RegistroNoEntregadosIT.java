package cl.agrotrack.deliveries.infraestructura.mensajeria;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.testcontainers.containers.RabbitMQContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * RabbitMQ real: un comando que el broker no puede enrutar, o que rechaza,
 * queda registrado en el log con su eventId y su cuerpo, y se cuenta.
 */
@ExtendWith(OutputCaptureExtension.class)
class RegistroNoEntregadosIT {

    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management");
    static CachingConnectionFactory cf;
    static RabbitTemplate rabbit;
    static SimpleMeterRegistry registry;

    @BeforeAll
    static void arrancar() {
        RABBIT.start();
        cf = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        cf.setUsername(RABBIT.getAdminUsername());
        cf.setPassword(RABBIT.getAdminPassword());
        // Lo mismo que application.yml: publisher-confirm-type correlated y publisher-returns
        cf.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        cf.setPublisherReturns(true);
        rabbit = new RabbitTemplate(cf);
        registry = new SimpleMeterRegistry();
        new RegistroNoEntregados(registry).registrarEn(rabbit);

        // Solo el exchange, sin colas: nada queda enrutado
        new RabbitAdmin(cf).declareExchange(new DirectExchange("cmd.direct", true, false));
    }

    @AfterAll
    static void parar() {
        cf.destroy();
        RABBIT.stop();
    }

    private static Message comando(String eventId, String cuerpo) {
        return MessageBuilder.withBody(cuerpo.getBytes(StandardCharsets.UTF_8)).setMessageId(eventId).build();
    }

    private static double noEntregados() {
        return registry.get("agrotrack.deliveries.comandos.no_entregados").counter().count();
    }

    @Test
    @DisplayName("Routing key sin cola: RabbitMQ lo devuelve (mandatory) y queda en el log para reinyectar")
    void noEnrutado(CapturedOutput salida) {
        double antes = noEntregados();
        String cuerpo = "{\"type\":\"email.send\",\"eventId\":\"ev-sin-cola\"}";

        rabbit.send("cmd.direct", "nadie.escucha", comando("ev-sin-cola", cuerpo),
                new RegistroNoEntregados.ComandoPublicado("ev-sin-cola", cuerpo));

        await().atMost(Duration.ofSeconds(10)).until(() -> salida.getOut().contains("NO ENRUTADO eventId=ev-sin-cola"));
        assertThat(salida.getOut()).contains("routingKey=nadie.escucha").contains(cuerpo);
        assertThat(noEntregados()).isEqualTo(antes + 1);
    }

    @Test
    @DisplayName("Exchange inexistente: el broker hace NACK y queda en el log con el eventId y el cuerpo")
    void rechazadoPorElBroker(CapturedOutput salida) {
        double antes = noEntregados();
        String cuerpo = "{\"type\":\"voucher.gen\",\"eventId\":\"ev-sin-exchange\"}";

        rabbit.send("no.existe", "voucher.gen", comando("ev-sin-exchange", cuerpo),
                new RegistroNoEntregados.ComandoPublicado("ev-sin-exchange", cuerpo));

        await().atMost(Duration.ofSeconds(10))
                .until(() -> salida.getOut().contains("RECHAZADO POR EL BROKER eventId=ev-sin-exchange"));
        assertThat(salida.getOut()).contains(cuerpo);
        assertThat(noEntregados()).isEqualTo(antes + 1);
    }
}
