package cl.agrotrack.notify.config;

import cl.agrotrack.notify.aplicacion.MensajeInvalidoException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.policy.SimpleRetryPolicy;

import java.util.Map;

/**
 * Politica de consumo (docs/02-contrato-de-eventos.md):
 * <ul>
 *   <li>ACK/NACK gobernados por el resultado del listener (modo AUTO del
 *       contenedor; el broker nunca auto-confirma): retorno normal = ACK,
 *       excepcion = NACK sin requeue.</li>
 *   <li>3 intentos con backoff 1s, 4s, 16s para fallos transitorios (SMTP caido, disco lleno).</li>
 *   <li>Un mensaje invalido no se reintenta: va directo a la DLQ.</li>
 *   <li>Agotados los intentos, NACK sin requeue: el broker lo manda a la DLQ
 *       (x-dead-letter-exchange declarado por mq-admin) y se cuenta en la
 *       metrica {@code agrotrack.notify.dlq.total}, la "tasa de DLQ" del enunciado.</li>
 * </ul>
 */
@Configuration
public class RabbitConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitConfig.class);

    public static final int MAX_INTENTOS = 3;

    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            MeterRegistry registry) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);

        Counter aDlq = Counter.builder("agrotrack.notify.dlq.total")
                .description("Mensajes enviados a DLQ tras agotar reintentos o por ser invalidos")
                .register(registry);

        // Reintenta todo salvo MensajeInvalidoException (traversa causas: true)
        SimpleRetryPolicy politica = new SimpleRetryPolicy(MAX_INTENTOS,
                Map.of(MensajeInvalidoException.class, false, Exception.class, true), true);

        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .retryPolicy(politica)
                .backOffOptions(1_000, 4.0, 16_000)
                .recoverer(contarYRechazar(aDlq))
                .build());
        return factory;
    }

    private static MessageRecoverer contarYRechazar(Counter aDlq) {
        MessageRecoverer rechazar = new RejectAndDontRequeueRecoverer();
        return (Message message, Throwable cause) -> {
            aDlq.increment();
            log.error("[dlq] {} de {} -> DLQ. Causa: {}",
                    message.getMessageProperties().getMessageId(),
                    message.getMessageProperties().getConsumerQueue(),
                    cause.getMessage());
            rechazar.recover(message, cause);
        };
    }
}
