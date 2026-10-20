package cl.agrotrack.mqadmin;

import cl.agrotrack.mqadmin.aplicacion.AdministracionInvalidaException;
import cl.agrotrack.mqadmin.aplicacion.AdministracionInvalidaException.Motivo;
import cl.agrotrack.mqadmin.aplicacion.RabbitAdminService;
import cl.agrotrack.mqadmin.aplicacion.RabbitAdminService.TipoExchange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La administracion contra un RabbitMQ real: lo que la API crea, el broker lo
 * tiene; lo que borra, desaparece; y los errores del broker se traducen bien.
 */
@SpringBootTest
@Testcontainers
class AdministracionIT {

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management");

    @Autowired RabbitAdminService servicio;
    @Autowired AmqpAdmin admin;
    @Autowired RabbitTemplate rabbit;
    @Autowired ConnectionFactory connectionFactory;

    @Test
    @DisplayName("Crear exchange topic + cola con DLQ + binding: un mensaje viaja y, rechazado, cae en la DLQ")
    void flujoCompleto() throws Exception {
        servicio.crearExchange("ex.it", TipoExchange.TOPIC, true);
        servicio.crearCola("q.it", true, true);
        servicio.crearBinding("q.it", "ex.it", "it.#");

        rabbit.convertAndSend("ex.it", "it.algo", "hola");

        try (var conn = connectionFactory.createConnection(); var canal = conn.createChannel(false)) {
            var entregado = canal.basicGet("q.it", false);
            assertThat(entregado).as("el mensaje llego a la cola por el binding topic").isNotNull();
            assertThat(new String(entregado.getBody(), StandardCharsets.UTF_8)).isEqualTo("hola");
            canal.basicNack(entregado.getEnvelope().getDeliveryTag(), false, false);
        }

        Thread.sleep(300);
        assertThat(admin.getQueueInfo("q.it.dlq").getMessageCount()).as("el rechazado esta en la DLQ").isEqualTo(1);
    }

    @Test
    @DisplayName("Eliminar una cola creada por la API la saca del broker")
    void eliminar() {
        servicio.crearCola("q.it.borrar", true, false);
        assertThat(admin.getQueueInfo("q.it.borrar")).isNotNull();

        servicio.eliminarCola("q.it.borrar");

        assertThat(admin.getQueueInfo("q.it.borrar")).isNull();
    }

    @Test
    @DisplayName("Redeclarar una cola con otra durabilidad: el PRECONDITION_FAILED real se traduce a conflicto")
    void conflictoReal() {
        servicio.crearCola("q.it.choca", true, false);

        assertThatThrownBy(() -> servicio.crearCola("q.it.choca", false, false))
                .isInstanceOf(AdministracionInvalidaException.class)
                .extracting(e -> ((AdministracionInvalidaException) e).motivo())
                .isEqualTo(Motivo.CONFLICTO_CON_EXISTENTE);
    }

    @Test
    @DisplayName("Un binding a un exchange que no existe: el NOT_FOUND real se traduce a NO_EXISTE")
    void exchangeInexistenteReal() {
        servicio.crearCola("q.it.huerfana", true, false);

        assertThatThrownBy(() -> servicio.crearBinding("q.it.huerfana", "ex.no.existe", "x"))
                .isInstanceOf(AdministracionInvalidaException.class)
                .extracting(e -> ((AdministracionInvalidaException) e).motivo())
                .isEqualTo(Motivo.NO_EXISTE);
    }

    @Test
    @DisplayName("Las colas del sistema siguen ahi despues de intentar borrarlas por la API")
    void sistemaIntacto() {
        assertThatThrownBy(() -> servicio.eliminarCola("q.cmd.email"))
                .isInstanceOf(AdministracionInvalidaException.class);
        assertThat(admin.getQueueInfo("q.cmd.email")).isNotNull();
    }
}
