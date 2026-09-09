package cl.agrotrack.deliveries.infraestructura.persistencia;

import cl.agrotrack.deliveries.dominio.EstadoEntrega;
import cl.agrotrack.deliveries.soporte.PostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EntregaRepositoryIT extends PostgresIT {

    @Autowired EntregaRepository repo;

    private Entrega nueva(String codigo, String productor) {
        return new Entrega(codigo, productor, 1L, 1L, new BigDecimal("100"));
    }

    @Test
    @DisplayName("Guardar y leer por codigo; el estado inicial es REGISTRADA con fecha de registro")
    void guardarYLeer() {
        repo.saveAndFlush(nueva("DEL-2026-000001", "prod-a"));

        Entrega e = repo.findByCodigo("DEL-2026-000001").orElseThrow();
        assertThat(e.getEstado()).isEqualTo(EstadoEntrega.REGISTRADA);
        assertThat(e.getFechaRegistro()).isCloseTo(Instant.now(), org.assertj.core.api.Assertions.within(1, ChronoUnit.MINUTES));
        assertThat(e.getFechaRecepcion()).isNull();
        assertThat(e.getVersion()).isNotNull();
    }

    @Test
    @DisplayName("La secuencia de codigos avanza de a uno")
    void secuenciaDeCodigos() {
        long a = repo.siguienteNumeroDeCodigo();
        long b = repo.siguienteNumeroDeCodigo();
        assertThat(b).isEqualTo(a + 1);
    }

    @Test
    @DisplayName("El filtro combina estado, rango de fechas y productor")
    void filtro() {
        Entrega recibida = nueva("DEL-2026-000010", "prod-f");
        recibida.transicionar(EstadoEntrega.RECIBIDA, new BigDecimal("98.5"), null, Instant.now());
        repo.saveAndFlush(recibida);
        repo.saveAndFlush(nueva("DEL-2026-000011", "prod-f"));
        repo.saveAndFlush(nueva("DEL-2026-000012", "otro"));

        List<Entrega> soloRecibidas = repo.findAll(EntregaRepository.filtro(EstadoEntrega.RECIBIDA, null, null, null));
        assertThat(soloRecibidas).extracting(Entrega::getCodigo).contains("DEL-2026-000010").doesNotContain("DEL-2026-000011");

        List<Entrega> deProdF = repo.findAll(EntregaRepository.filtro(null, null, null, "prod-f"));
        assertThat(deProdF).extracting(Entrega::getCodigo)
                .contains("DEL-2026-000010", "DEL-2026-000011").doesNotContain("DEL-2026-000012");

        Instant manana = Instant.now().plus(1, ChronoUnit.DAYS);
        assertThat(repo.findAll(EntregaRepository.filtro(null, manana, null, null))).isEmpty();
        assertThat(repo.findAll(EntregaRepository.filtro(null, null, manana, "prod-f"))).hasSize(2);
    }

    @Test
    @DisplayName("transicionar a RECIBIDA guarda peso y fecha de recepcion; a DESPACHADA la fecha de despacho")
    void fechasDeCiclo() {
        Entrega e = nueva("DEL-2026-000020", "prod-c");
        Instant t1 = Instant.parse("2026-09-07T10:00:00Z");
        Instant t2 = Instant.parse("2026-09-07T15:30:00Z");
        e.transicionar(EstadoEntrega.RECIBIDA, new BigDecimal("95"), null, t1);
        e.transicionar(EstadoEntrega.EN_CLASIFICACION, null, null, t1);
        e.transicionar(EstadoEntrega.EN_DESPACHO, null, null, t1);
        e.transicionar(EstadoEntrega.DESPACHADA, null, null, t2);
        repo.saveAndFlush(e);

        Entrega leida = repo.findByCodigo("DEL-2026-000020").orElseThrow();
        assertThat(leida.getPesoRecibido()).isEqualByComparingTo("95");
        assertThat(leida.cantidadEnBodega()).isEqualByComparingTo("95");
        assertThat(leida.getFechaRecepcion()).isEqualTo(t1);
        assertThat(leida.getFechaDespacho()).isEqualTo(t2);
    }
}
