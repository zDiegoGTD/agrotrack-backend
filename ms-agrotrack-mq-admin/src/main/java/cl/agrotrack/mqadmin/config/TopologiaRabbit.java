package cl.agrotrack.mqadmin.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * La topologia de RabbitMQ del enunciado (seccion 8), declarada en codigo.
 *
 * <p>Spring AMQP la crea al arrancar (RabbitAdmin la recorre y hace los
 * declare). Declarar es idempotente: si ya existe con los mismos
 * atributos no pasa nada; si existe con otros, falla fuerte — que es lo
 * que se quiere: nadie la cambio a mano por la UI sin que se note.
 *
 * <p>Por que un servicio aparte y no cada consumidor declarando lo suyo:
 * hay una sola fuente de verdad, versionada en git, y en AWS Academy
 * (donde las EC2 se apagan solas) la topologia vuelve entera con un
 * {@code docker compose up}.
 */
@Configuration
public class TopologiaRabbit {

    public static final String EXCHANGE_DIRECT = "cmd.direct";
    public static final String EXCHANGE_TOPIC = "cmd.topic";
    public static final String EXCHANGE_DLX = "cmd.dead.dlx";

    /** Un flujo = cola + su DLQ + sus dos bindings. */
    public record Flujo(String cola, String routingKeyDirect, String routingKeyTopic, String proposito) {
        public String dlq() {
            return cola + ".dlq";
        }
    }

    public static final List<Flujo> FLUJOS = List.of(
            new Flujo("q.cmd.email", "email.send", "email.*",
                    "Email/push al productor (recibida, clasificada, despachada, rechazada)"),
            new Flujo("q.cmd.receipt", "receipt.ticket", "receipt.#",
                    "Ticket de recepcion / pesaje del lote en bodega"),
            new Flujo("q.cmd.voucher", "voucher.gen", "voucher.*",
                    "Generacion de PDF (guia de despacho o comprobante de acopio)"));

    @Bean
    public Declarables topologia() {
        DirectExchange direct = new DirectExchange(EXCHANGE_DIRECT, true, false);
        TopicExchange topic = new TopicExchange(EXCHANGE_TOPIC, true, false);
        DirectExchange dlx = new DirectExchange(EXCHANGE_DLX, true, false);

        List<Declarable> declarables = new ArrayList<>(List.of(direct, topic, dlx));

        for (Flujo f : FLUJOS) {
            Queue dlq = QueueBuilder.durable(f.dlq()).build();
            Queue cola = QueueBuilder.durable(f.cola())
                    // Un NACK sin requeue manda el mensaje aqui, con su envelope
                    // intacto y la cabecera x-death explicando por que.
                    .deadLetterExchange(EXCHANGE_DLX)
                    .deadLetterRoutingKey(f.dlq())
                    .build();

            declarables.add(dlq);
            declarables.add(cola);
            declarables.add(BindingBuilder.bind(cola).to(direct).with(f.routingKeyDirect()));
            declarables.add(BindingBuilder.bind(cola).to(topic).with(f.routingKeyTopic()));
            declarables.add(BindingBuilder.bind(dlq).to(dlx).with(f.dlq()));
        }
        return new Declarables(declarables);
    }

    /** Solo para reportar: cuantos bindings deberia haber. */
    public static int bindingsEsperados() {
        return FLUJOS.size() * 3;
    }

    /** Para el controlador: la lista de bindings tal como se declararon. */
    public static List<Binding> bindingsDe(Declarables d) {
        return d.getDeclarablesByType(Binding.class);
    }
}
