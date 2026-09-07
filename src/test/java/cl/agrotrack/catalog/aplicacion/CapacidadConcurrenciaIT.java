package cl.agrotrack.catalog.aplicacion;

import cl.agrotrack.catalog.aplicacion.Dtos.CapacidadRequest;
import cl.agrotrack.catalog.dominio.CapacidadInsuficienteException;
import cl.agrotrack.catalog.infraestructura.persistencia.Bodega;
import cl.agrotrack.catalog.infraestructura.persistencia.BodegaRepository;
import cl.agrotrack.catalog.soporte.OracleIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La razon de ser del @Version en BODEGA. Veinte operadores reservando 10
 * en una bodega de 100 a la vez: tienen que caber exactamente diez, ni uno
 * mas (sobreventa) ni uno menos (reserva perdida).
 */
class CapacidadConcurrenciaIT extends OracleIT {

    @Autowired CapacidadService capacidad;
    @Autowired BodegaRepository bodegas;

    @Test
    @DisplayName("20 reservas concurrentes de 10 sobre capacidad 100: exactamente 10 entran")
    void sinSobreventaNiReservasPerdidas() throws Exception {
        Bodega b = bodegas.saveAndFlush(new Bodega("Concurrente", "Talca", new BigDecimal("100")));
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger sinEspacio = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            List<Callable<Void>> tareas = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                String entrega = "DEL-T-" + i;
                tareas.add(() -> {
                    try {
                        capacidad.reservar(b.getId(), new CapacidadRequest(BigDecimal.TEN, entrega));
                        ok.incrementAndGet();
                    } catch (CapacidadInsuficienteException e) {
                        sinEspacio.incrementAndGet();
                    }
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(tareas)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(ok.get()).isEqualTo(10);
        assertThat(sinEspacio.get()).isEqualTo(10);
        assertThat(bodegas.findById(b.getId()).orElseThrow().getCapacidadDisponible())
                .isEqualByComparingTo("0");
    }
}
