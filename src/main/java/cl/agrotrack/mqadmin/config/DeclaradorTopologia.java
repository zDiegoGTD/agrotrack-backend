package cl.agrotrack.mqadmin.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Declara la topologia al arrancar, de forma explicita y con reintentos.
 *
 * <p>Por que no basta con el bean {@link Declarables}: {@code RabbitAdmin} lo
 * aplica de forma perezosa, cuando algo abre la primera conexion AMQP. Este
 * servicio no publica ni consume nada — solo declara — asi que esa conexion
 * podia no llegar nunca y la topologia quedaba sin crear mientras el servicio
 * se reportaba sano. Lo que sigue fuerza la declaracion en el arranque.
 *
 * <p>Los reintentos existen porque en AWS este contenedor puede arrancar antes
 * de que el cluster de RabbitMQ termine de formarse (con Khepri, declarar
 * exige quorum del store de metadatos, y durante el join no lo hay).
 */
@Component
public class DeclaradorTopologia {

    private static final Logger log = LoggerFactory.getLogger(DeclaradorTopologia.class);
    private static final int MAX_INTENTOS = 30;
    private static final long ESPERA_MS = 5_000;

    private final AmqpAdmin admin;
    private final Declarables topologia;

    public DeclaradorTopologia(AmqpAdmin admin, Declarables topologia) {
        this.admin = admin;
        this.topologia = topologia;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void declarar() {
        for (int intento = 1; intento <= MAX_INTENTOS; intento++) {
            try {
                declararTodo();
                log.info("Topologia declarada: {} exchanges, {} colas, {} bindings",
                        contar(Exchange.class), contar(Queue.class), contar(Binding.class));
                return;
            } catch (RuntimeException e) {
                log.warn("RabbitMQ aun no acepta la topologia (intento {}/{}): {}",
                        intento, MAX_INTENTOS, e.getMessage());
                dormir();
            }
        }
        // Si la topologia no existe, notify no puede consumir y deliveries no
        // puede publicar: es mejor que el contenedor muera y Docker lo reinicie
        // que quedarse "sano" sin haber hecho su unico trabajo.
        throw new IllegalStateException("No se pudo declarar la topologia de RabbitMQ tras "
                + MAX_INTENTOS + " intentos");
    }

    /** El orden importa: exchanges y colas antes que los bindings que los unen. */
    private void declararTodo() {
        topologia.getDeclarablesByType(Exchange.class).forEach(admin::declareExchange);
        topologia.getDeclarablesByType(Queue.class).forEach(admin::declareQueue);
        topologia.getDeclarablesByType(Binding.class).forEach(admin::declareBinding);
    }

    private int contar(Class<? extends Declarable> tipo) {
        return topologia.getDeclarablesByType(tipo).size();
    }

    private static void dormir() {
        try {
            Thread.sleep(ESPERA_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido esperando a RabbitMQ", e);
        }
    }
}
