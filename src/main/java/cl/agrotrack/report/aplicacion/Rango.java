package cl.agrotrack.report.aplicacion;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * El parametro {@code range} del enunciado: {@code last24h}, {@code last7d}.
 * Se acepta la forma general {@code last<N>(h|d)} hasta 90 dias.
 */
public record Rango(Instant desde, Instant hasta, String etiqueta) {

    private static final Pattern FORMA = Pattern.compile("^last(\\d{1,3})([hd])$");
    private static final Duration MAXIMO = Duration.ofDays(90);

    public static Rango parse(String texto, Clock clock) {
        String t = texto == null || texto.isBlank() ? "last24h" : texto.trim().toLowerCase();
        Matcher m = FORMA.matcher(t);
        if (!m.matches()) {
            throw new IllegalArgumentException("range invalido: use last24h, last7d, last30d (last<N>h|d)");
        }
        long n = Long.parseLong(m.group(1));
        Duration d = m.group(2).equals("h") ? Duration.ofHours(n) : Duration.ofDays(n);
        if (d.isZero() || d.compareTo(MAXIMO) > 0) {
            throw new IllegalArgumentException("range fuera de limites: entre 1h y 90d");
        }
        Instant hasta = Instant.now(clock);
        return new Rango(hasta.minus(d), hasta, t);
    }
}
