package cl.agrotrack.notify.infraestructura.mensajeria;

import cl.agrotrack.notify.aplicacion.VoucherService;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Dominio <b>despacho</b>: genera el PDF de la guia de despacho o del
 * comprobante de acopio. El nombre de la cola viene de
 * {@code agrotrack.rabbit.colas.voucher}.
 */
@Component
public class VoucherListener {

    private final ProcesadorComandos procesador;
    private final VoucherService vouchers;

    public VoucherListener(ProcesadorComandos procesador, VoucherService vouchers) {
        this.procesador = procesador;
        this.vouchers = vouchers;
    }

    @RabbitListener(queues = "${agrotrack.rabbit.colas.voucher}")
    public void alRecibir(Message m, Channel canal, @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {
        procesador.procesar(m, canal, tag, vouchers::generar);
    }
}
