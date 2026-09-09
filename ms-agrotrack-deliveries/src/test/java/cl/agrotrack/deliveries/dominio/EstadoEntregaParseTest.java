package cl.agrotrack.deliveries.dominio;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EstadoEntregaParseTest {

    @ParameterizedTest
    @CsvSource({
            "EN_CLASIFICACIÓN, EN_CLASIFICACION",
            "en clasificación, EN_CLASIFICACION",
            "en-clasificacion, EN_CLASIFICACION",
            "  recibida , RECIBIDA",
            "Despachada, DESPACHADA",
    })
    void normalizaTildesCajaYSeparadores(String entrada, EstadoEntrega esperado) {
        assertThat(EstadoEntrega.parse(entrada)).isEqualTo(esperado);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "VOLANDO", "RECIBIDO"})
    void rechazaLoQueNoEsUnEstado(String entrada) {
        assertThatThrownBy(() -> EstadoEntrega.parse(entrada)).isInstanceOf(IllegalArgumentException.class);
    }
}
