package cl.agrotrack.report.aplicacion;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lado de lectura: el panel de KPIs de la seccion 3 del enunciado. */
@Service
@Transactional(readOnly = true)
public class ReportService {

    private final JdbcTemplate jdbc;

    public ReportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record EntregasHora(Instant hora, long registradas, long recibidas, long despachadas, long rechazadas) {
    }

    public record Kpis(String range, Instant desde, Instant hasta,
                       List<EntregasHora> entregasPorHora,
                       Double tiempoCicloPromedioMin, long entregasCerradas,
                       Map<String, Long> estadosActivos, long totalActivas) {
    }

    public record TopProducto(Long productoId, long entregas, BigDecimal pesoTotal) {
    }

    public Kpis kpis(Rango r) {
        Timestamp desde = Timestamp.from(r.desde());
        Timestamp hasta = Timestamp.from(r.hasta());

        List<EntregasHora> porHora = jdbc.query("""
                SELECT hora, SUM(registradas) r, SUM(recibidas) rc, SUM(despachadas) d, SUM(rechazadas) x
                FROM kpi_entregas_hora WHERE hora >= ? AND hora <= ?
                GROUP BY hora ORDER BY hora
                """, (rs, i) -> new EntregasHora(rs.getTimestamp("hora").toInstant(),
                rs.getLong("r"), rs.getLong("rc"), rs.getLong("d"), rs.getLong("x")), desde, hasta);

        Map<String, Object> ciclo = jdbc.queryForMap("""
                SELECT AVG(minutos_ciclo) promedio, COUNT(minutos_ciclo) cerradas
                FROM ciclo_entrega WHERE estado = 'DESPACHADA' AND fecha_cierre >= ? AND fecha_cierre <= ?
                """, desde, hasta);
        Number promedio = (Number) ciclo.get("promedio");
        Number cerradas = (Number) ciclo.get("cerradas");

        Map<String, Long> activos = new LinkedHashMap<>();
        for (String e : List.of("REGISTRADA", "RECIBIDA", "EN_CLASIFICACION", "EN_DESPACHO")) {
            activos.put(e, 0L);
        }
        jdbc.query("""
                SELECT estado, COUNT(*) n FROM ciclo_entrega
                WHERE estado NOT IN ('DESPACHADA','RECHAZADA') GROUP BY estado
                """, rs -> {
            activos.put(rs.getString("estado"), rs.getLong("n"));
        });
        long totalActivas = activos.values().stream().mapToLong(Long::longValue).sum();

        return new Kpis(r.etiqueta(), r.desde(), r.hasta(), porHora,
                promedio == null ? null : Math.round(promedio.doubleValue() * 10) / 10.0,
                cerradas == null ? 0 : cerradas.longValue(),
                activos, totalActivas);
    }

    /** "Productos mas recibidos" (pantalla de reporteria): por cantidad de lotes recibidos en el rango. */
    public List<TopProducto> topProductos(Rango r, int limite) {
        return jdbc.query("""
                SELECT producto_id, COUNT(*) entregas, COALESCE(SUM(peso), 0) peso
                FROM ciclo_entrega
                WHERE fecha_recepcion >= ? AND fecha_recepcion <= ? AND producto_id IS NOT NULL
                GROUP BY producto_id ORDER BY entregas DESC, peso DESC
                LIMIT ?
                """, (rs, i) -> new TopProducto(rs.getLong("producto_id"), rs.getLong("entregas"), rs.getBigDecimal("peso")),
                Timestamp.from(r.desde()), Timestamp.from(r.hasta()), Math.max(1, Math.min(limite, 50)));
    }
}
