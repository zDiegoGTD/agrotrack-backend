package cl.agrotrack.mqadmin;

import cl.agrotrack.mqadmin.config.TopologiaRabbit;
import cl.agrotrack.mqadmin.config.TopologiaRabbit.Flujo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La topologia tiene que existir por el solo hecho de arrancar el servicio,
 * sin que nadie publique ni consuma nada.
 *
 * <p>Este test cubre el fallo que aparecio en AWS: el bean Declarables solo
 * se aplicaba cuando alguien abria una conexion AMQP, y mq-admin no abre
 * ninguna. El servicio quedaba sano y sin colas creadas.
 */
@SpringBootTest
@Testcontainers
class DeclaradorTopologiaIT {

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management");

    @Autowired AmqpAdmin admin;

    @Test
    @DisplayName("Tras el arranque, las 6 colas existen sin haber publicado ni consumido nada")
    void topologiaDeclaradaAlArrancar() {
        for (Flujo f : TopologiaRabbit.FLUJOS) {
            for (String nombre : List.of(f.cola(), f.dlq())) {
                QueueInformation info = admin.getQueueInfo(nombre);
                assertThat(info).as("cola %s declarada al arrancar", nombre).isNotNull();
            }
        }
    }
}
