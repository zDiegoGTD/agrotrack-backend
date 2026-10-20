package cl.agrotrack.notify.infraestructura.mensajeria;

import cl.agrotrack.notify.aplicacion.EmailService;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Dominio <b>notificaciones</b>: correo al productor cuando su lote cambia de
 * estado (recibido, en clasificacion, despachado, rechazado).
 * El nombre de la cola viene de {@code agrotrack.rabbit.colas.email}.
 */
@Component
public class EmailListener {

    private final ProcesadorComandos procesador;
    private final EmailService emails;

    public EmailListener(ProcesadorComandos procesador, EmailService emails) {
        this.procesador = procesador;
        this.emails = emails;
    }

    @RabbitListener(queues = "${agrotrack.rabbit.colas.email}")
    public void alRecibir(Message m, Channel canal, @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {
        procesador.procesar(m, canal, tag, emails::enviar);
    }
}
