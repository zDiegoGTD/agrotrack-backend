package cl.agrotrack.notify.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Contenedor de consumidores de RabbitMQ (docs/02-contrato-de-eventos.md).
 *
 * <p>Confirmacion <b>manual</b>: el contenedor no confirma nada por su cuenta.
 * Cada consumidor decide ACK, NACK a la DLQ o reintento en
 * {@code ProcesadorComandos}, que es donde estan documentadas las rutas.
 *
 * <p>La topologia (colas, DLQ, exchanges) no se declara aqui: es de
 * ms-agrotrack-mq-admin. notify solo consume, con los nombres de
 * {@code agrotrack.rabbit.colas} en application.yml.
 */
@Configuration
@EnableConfigurationProperties(PropiedadesMensajeria.class)
public class RabbitConfig {

    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            SimpleRabbitListenerContainerFactoryConfigurer configurer) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        // Si un listener lanzara algo no previsto, el mensaje va a la DLQ en vez de volver
        // al frente de la cola y bloquearla.
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
