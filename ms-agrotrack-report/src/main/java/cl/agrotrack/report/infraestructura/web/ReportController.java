package cl.agrotrack.report.infraestructura.web;

import cl.agrotrack.report.aplicacion.Rango;
import cl.agrotrack.report.aplicacion.ReportService;
import cl.agrotrack.report.aplicacion.ReportService.Kpis;
import cl.agrotrack.report.aplicacion.ReportService.TopProducto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;

/** Endpoints de la seccion 5 del enunciado. Solo Admin (seccion 3 y 6). */
@RestController
@RequestMapping("/api/report")
@PreAuthorize("hasRole('ADMIN')")
public class ReportController {

    private final ReportService report;
    private final Clock clock;

    public ReportController(ReportService report, Clock clock) {
        this.report = report;
        this.clock = clock;
    }

    /** GET /api/report/kpis?range=last24h */
    @GetMapping("/kpis")
    public Kpis kpis(@RequestParam(defaultValue = "last24h") String range) {
        return report.kpis(Rango.parse(range, clock));
    }

    /** GET /api/report/top-services?range=last7d — "servicios" en el enunciado = productos aqui. */
    @GetMapping({"/top-services", "/top-productos"})
    public List<TopProducto> topProductos(@RequestParam(defaultValue = "last7d") String range,
                                          @RequestParam(defaultValue = "10") int limite) {
        return report.topProductos(Rango.parse(range, clock), limite);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail rangoInvalido(IllegalArgumentException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        p.setTitle("Bad Request");
        return p;
    }
}
