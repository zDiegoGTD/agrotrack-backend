package cl.agrotrack.deliveries.infraestructura.mensajeria;

import cl.agrotrack.deliveries.aplicacion.PublicadorEventos;
import cl.agrotrack.deliveries.dominio.Actor;
import cl.agrotrack.deliveries.dominio.Efecto;
import cl.agrotrack.deliveries.dominio.EstadoEntrega;
import cl.agrotrack.deliveries.infraestructura.persistencia.Entrega;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Traduce lo que decidio el dominio a mensajes reales.
 *
 * <p><b>Kafka lleva hechos, RabbitMQ lleva comandos</b> (docs/02). Por cada
 * transicion se publica primero el hecho en {@code deliveries.events}
 * (clave = codigo de la entrega, para que auditoria los reciba en orden)
 * y despues un comando por cada efecto asincrono en {@code cmd.direct}.
 *
 * <p>Se publica <b>despues del commit</b>. Si se publicara dentro de la
 * transaccion y la base rechazara el commit, se habria anunciado algo que
 * nunca paso. El costo es la ventana inversa (commit ok, broker caido): se
 * loguea con el envelope completo para poder reinyectarlo; un outbox
 * transaccional queda como mejora si la pauta lo pide.
 */
public class PublicadorMensajeria implements PublicadorEventos {

    private static final Logger log = LoggerFactory.getLogger(PublicadorMensajeria.class);

    /** Efecto asincrono -> routing key del comando (binding direct de mq-admin). */
    static final Map<Efecto, String> COMANDOS = Map.of(
            Efecto.NOTIFICAR_PRODUCTOR, "email.send",
            Efecto.EMITIR_TICKET_BODEGA, "receipt.ticket",
            Efecto.GENERAR_GUIA_DESPACHO, "voucher.gen");

    private final KafkaTemplate<String, String> kafka;
    private final RabbitTemplate rabbit;
    private final ObjectMapper json;
    private final Envelopes envelopes;
    private final String topicoEventos;
    private final String exchangeComandos;

    public PublicadorMensajeria(KafkaTemplate<String, String> kafka, RabbitTemplate rabbit, ObjectMapper json,
                                Envelopes envelopes, String topicoEventos, String exchangeComandos) {
        this.kafka = kafka;
        this.rabbit = rabbit;
        this.json = json;
        this.envelopes = envelopes;
        this.topicoEventos = topicoEventos;
        this.exchangeComandos = exchangeComandos;
    }

    @Override
    public void entregaRegistrada(Entrega e, Actor actor) {
        Envelope hecho = envelopes.crear("delivery.registered", e.getCodigo(), actor, datosDe(e, null));
        despuesDelCommit(() -> publicarHecho(hecho));
    }

    @Override
    public void entregaTransicionada(Entrega e, EstadoEntrega anterior, Set<Efecto> efectos, Actor actor) {
        Map<String, Object> datos = datosDe(e, anterior);
        // Un solo traceId para el hecho y todos sus comandos: salen del mismo request.
        String traceId = Envelopes.traceIdActual();
        Envelope hecho = envelopes.crear(tipoDeHecho(e.getEstado()), e.getCodigo(), actor, datos, traceId);

        List<Envelope> comandos = efectos.stream()
                .filter(COMANDOS::containsKey)
                .sorted() // orden estable: el mismo input produce los mismos mensajes
                .map(ef -> envelopes.crear(COMANDOS.get(ef), e.getCodigo(), actor, datosDeComando(ef, datos), traceId))
                .toList();

        despuesDelCommit(() -> {
            publicarHecho(hecho);                 // primero el hecho (D5)
            comandos.forEach(this::publicarComando);
        });
    }

    // ---- publicacion ----

    private void publicarHecho(Envelope env) {
        String cuerpo = serializar(env);
        try {
            kafka.send(topicoEventos, env.subject(), cuerpo).get();
            log.info("[kafka] {} {} eventId={}", env.type(), env.subject(), env.eventId());
        } catch (Exception ex) {
            log.error("[kafka] NO PUBLICADO {} {} -> reinyectar: {}", env.type(), env.subject(), cuerpo, ex);
        }
    }

    private void publicarComando(Envelope env) {
        String cuerpo = serializar(env);
        try {
            var msg = MessageBuilder.withBody(cuerpo.getBytes(StandardCharsets.UTF_8))
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setContentEncoding(StandardCharsets.UTF_8.name())
                    .setMessageId(env.eventId())
                    .setCorrelationId(env.correlationId())
                    .setType(env.type())
                    .setHeader("traceId", env.traceId())
                    .setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT)
                    .build();
            rabbit.send(exchangeComandos, env.type(), msg);
            log.info("[rabbit] {} {} eventId={}", env.type(), env.subject(), env.eventId());
        } catch (Exception ex) {
            log.error("[rabbit] NO PUBLICADO {} {} -> reinyectar: {}", env.type(), env.subject(), cuerpo, ex);
        }
    }

    private void despuesDelCommit(Runnable accion) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    accion.run();
                }
            });
        } else {
            accion.run();
        }
    }

    private String serializar(Envelope env) {
        try {
            return json.writeValueAsString(env);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar el envelope " + env.type(), e);
        }
    }

    // ---- payloads ----

    static String tipoDeHecho(EstadoEntrega estado) {
        return switch (estado) {
            case REGISTRADA -> "delivery.registered";
            case RECIBIDA -> "delivery.received";
            case EN_CLASIFICACION -> "delivery.classifying";
            case EN_DESPACHO -> "delivery.dispatching";
            case DESPACHADA -> "delivery.dispatched";
            case RECHAZADA -> "delivery.rejected";
        };
    }

    static Map<String, Object> datosDe(Entrega e, EstadoEntrega anterior) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("codigo", e.getCodigo());
        d.put("productorId", e.getProductorId());
        d.put("productoId", e.getProductoId());
        d.put("bodegaId", e.getBodegaId());
        d.put("cantidad", e.getCantidad());
        d.put("pesoRecibido", e.getPesoRecibido());
        d.put("estadoAnterior", anterior != null ? anterior.name() : null);
        d.put("estado", e.getEstado().name());
        d.put("motivoRechazo", e.getMotivoRechazo());
        d.put("fechaRegistro", e.getFechaRegistro());
        d.put("fechaRecepcion", e.getFechaRecepcion());
        d.put("fechaDespacho", e.getFechaDespacho());
        return d;
    }

    /** El comando lleva los mismos datos mas la plantilla que corresponde al efecto. */
    static Map<String, Object> datosDeComando(Efecto efecto, Map<String, Object> datos) {
        Map<String, Object> d = new LinkedHashMap<>(datos);
        d.put("plantilla", switch (efecto) {
            case NOTIFICAR_PRODUCTOR -> "entrega-" + ((String) datos.get("estado")).toLowerCase();
            case EMITIR_TICKET_BODEGA -> "ticket-recepcion";
            case GENERAR_GUIA_DESPACHO -> "guia-despacho";
            default -> "sin-plantilla";
        });
        return d;
    }
}
