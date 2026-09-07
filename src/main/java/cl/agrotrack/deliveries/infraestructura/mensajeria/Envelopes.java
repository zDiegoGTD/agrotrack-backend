package cl.agrotrack.deliveries.infraestructura.mensajeria;

import cl.agrotrack.deliveries.dominio.Actor;
import org.slf4j.MDC;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/** Fabrica de envelopes: ids, trazas y correlacion consistentes. */
public final class Envelopes {

    public static final String SOURCE = "ms-agrotrack-deliveries";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Clock clock;

    public Envelopes(Clock clock) {
        this.clock = clock;
    }

    public Envelope crear(String type, String subject, Actor actor, Map<String, Object> data) {
        return crear(type, subject, actor, data, traceIdActual());
    }

    /**
     * Con traceId explicito: todos los mensajes que salen de un mismo request
     * (el hecho y sus comandos) tienen que compartirlo. Quien publica lo
     * genera una vez y lo pasa aqui.
     */
    public Envelope crear(String type, String subject, Actor actor, Map<String, Object> data, String traceId) {
        return new Envelope(
                Envelope.SPEC_VERSION,
                type,
                uuidV7().toString(),
                Instant.now(clock),
                traceId,
                correlacionDe(subject),
                SOURCE,
                subject,
                new Envelope.Actor(actor.userId(), actor.nombre(), actor.rol().name()),
                data);
    }

    /**
     * Todos los mensajes de una misma entrega comparten correlationId, sin
     * necesidad de guardarlo: se deriva del codigo. Con eso auditoria
     * reconstruye el ciclo completo aunque los mensajes lleguen de fuentes
     * distintas.
     */
    static String correlacionDe(String subject) {
        return UUID.nameUUIDFromBytes(("agrotrack:" + subject).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** Si hay un trazador (MDC traceId) se respeta; si no, uno nuevo con formato W3C (32 hex). */
    public static String traceIdActual() {
        String mdc = MDC.get("traceId");
        if (mdc != null && !mdc.isBlank()) {
            return mdc;
        }
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /**
     * UUID v7 (RFC 9562): 48 bits de milisegundos Unix + aleatorio. Ordenable
     * por tiempo, lo que ayuda a los indices de PROCESSED_EVENTS. Java 21 no
     * lo trae de serie.
     */
    public static UUID uuidV7() {
        long ms = System.currentTimeMillis();
        byte[] rnd = new byte[10];
        RANDOM.nextBytes(rnd);

        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putShort((short) (ms >>> 32));
        bb.putInt((int) ms);
        bb.put(rnd);
        bb.flip();

        long msb = bb.getLong();
        long lsb = bb.getLong();
        msb = (msb & 0xFFFF_FFFF_FFFF_0FFFL) | 0x7000L;                  // version 7
        lsb = (lsb & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;   // variante RFC
        return new UUID(msb, lsb);
    }
}
