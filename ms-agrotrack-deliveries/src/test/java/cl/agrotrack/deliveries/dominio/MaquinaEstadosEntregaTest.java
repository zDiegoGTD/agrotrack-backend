package cl.agrotrack.deliveries.dominio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static cl.agrotrack.deliveries.dominio.EstadoEntrega.*;
import static cl.agrotrack.deliveries.dominio.Rol.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Especificacion ejecutable de la maquina de estados (docs/01-maquina-de-estados.md).
 * No levanta Spring, no toca la base y no usa red: son milisegundos.
 */
class MaquinaEstadosEntregaTest {

    @Nested
    @DisplayName("Transiciones validas del enunciado")
    class TransicionesValidas {

        @Test
        @DisplayName("T2: recibir descuenta capacidad, avisa al productor y emite ticket de bodega")
        void t2_recibir() {
            var r = MaquinaEstadosEntrega.evaluar(REGISTRADA, RECIBIDA, OPERADOR);

            assertThat(r.permitida()).isTrue();
            assertThat(r.efectos()).containsExactlyInAnyOrder(
                    Efecto.DESCONTAR_CAPACIDAD,
                    Efecto.NOTIFICAR_PRODUCTOR,
                    Efecto.EMITIR_TICKET_BODEGA);
        }

        @Test
        @DisplayName("T3: clasificar solo avisa al productor")
        void t3_clasificar() {
            var r = MaquinaEstadosEntrega.evaluar(RECIBIDA, EN_CLASIFICACION, OPERADOR);

            assertThat(r.permitida()).isTrue();
            assertThat(r.efectos()).containsExactly(Efecto.NOTIFICAR_PRODUCTOR);
        }

        @Test
        @DisplayName("T4: pasar a despacho no dispara ningun efecto")
        void t4_a_despacho() {
            var r = MaquinaEstadosEntrega.evaluar(EN_CLASIFICACION, EN_DESPACHO, OPERADOR);

            assertThat(r.permitida()).isTrue();
            assertThat(r.efectos()).isEmpty();
        }

        @Test
        @DisplayName("T5: despachar genera la guia y avisa al productor")
        void t5_despachar() {
            var r = MaquinaEstadosEntrega.evaluar(EN_DESPACHO, DESPACHADA, OPERADOR);

            assertThat(r.permitida()).isTrue();
            assertThat(r.efectos()).containsExactlyInAnyOrder(
                    Efecto.GENERAR_GUIA_DESPACHO,
                    Efecto.NOTIFICAR_PRODUCTOR);
        }

        @Test
        @DisplayName("T6: rechazar antes de recibir NO devuelve capacidad, porque nunca se descconto")
        void t6_rechazar_sin_recibir() {
            var r = MaquinaEstadosEntrega.evaluar(REGISTRADA, RECHAZADA, OPERADOR);

            assertThat(r.permitida()).isTrue();
            assertThat(r.efectos()).containsExactly(Efecto.NOTIFICAR_PRODUCTOR);
            assertThat(r.efectos()).doesNotContain(Efecto.DEVOLVER_CAPACIDAD);
        }

        @Test
        @DisplayName("T7: rechazar despues de recibir devuelve la capacidad")
        void t7_rechazar_recibida() {
            var r = MaquinaEstadosEntrega.evaluar(RECIBIDA, RECHAZADA, OPERADOR);

            assertThat(r.permitida()).isTrue();
            assertThat(r.efectos()).containsExactlyInAnyOrder(
                    Efecto.DEVOLVER_CAPACIDAD,
                    Efecto.NOTIFICAR_PRODUCTOR);
        }

        @Test
        @DisplayName("T8: rechazar en clasificacion tambien devuelve la capacidad")
        void t8_rechazar_en_clasificacion() {
            var r = MaquinaEstadosEntrega.evaluar(EN_CLASIFICACION, RECHAZADA, OPERADOR);

            assertThat(r.permitida()).isTrue();
            assertThat(r.efectos()).contains(Efecto.DEVOLVER_CAPACIDAD);
        }
    }

    @Nested
    @DisplayName("La regla explicita del enunciado")
    class ReglaDelEnunciado {

        @Test
        @DisplayName("No se puede pasar a EN_DESPACHO sin haber RECIBIDO")
        void no_hay_despacho_sin_recibir() {
            var r = MaquinaEstadosEntrega.evaluar(REGISTRADA, EN_DESPACHO, OPERADOR);

            assertThat(r.permitida()).isFalse();
            assertThat(r.motivo()).isEqualTo(MotivoRechazo.TRANSICION_NO_VALIDA);
        }

        @Test
        @DisplayName("El unico camino a EN_DESPACHO pasa por RECIBIDA y EN_CLASIFICACION")
        void unico_camino_a_despacho() {
            for (EstadoEntrega origen : EstadoEntrega.values()) {
                boolean permitida = MaquinaEstadosEntrega
                        .evaluar(origen, EN_DESPACHO, OPERADOR).permitida();
                assertThat(permitida)
                        .as("desde %s hacia EN_DESPACHO", origen)
                        .isEqualTo(origen == EN_CLASIFICACION);
            }
        }
    }

    @Nested
    @DisplayName("Estados terminales")
    class Terminales {

        @ParameterizedTest
        @EnumSource(value = EstadoEntrega.class, names = {"DESPACHADA", "RECHAZADA"})
        @DisplayName("De un estado terminal no sale ninguna transicion")
        void terminal_no_transiciona(EstadoEntrega terminal) {
            for (EstadoEntrega destino : EstadoEntrega.values()) {
                var r = MaquinaEstadosEntrega.evaluar(terminal, destino, ADMIN);
                assertThat(r.permitida())
                        .as("%s -> %s", terminal, destino)
                        .isFalse();
                assertThat(r.motivo()).isEqualTo(MotivoRechazo.ESTADO_TERMINAL);
            }
        }

        @Test
        @DisplayName("DESPACHADA y RECHAZADA se declaran terminales")
        void declarados_terminales() {
            assertThat(DESPACHADA.esTerminal()).isTrue();
            assertThat(RECHAZADA.esTerminal()).isTrue();
            assertThat(REGISTRADA.esTerminal()).isFalse();
        }
    }

    @Nested
    @DisplayName("Permisos por rol")
    class Permisos {

        @Test
        @DisplayName("El productor no cambia estados: solo registra y consulta")
        void productor_no_cambia_estados() {
            var r = MaquinaEstadosEntrega.evaluar(REGISTRADA, RECIBIDA, CLIENTE);

            assertThat(r.permitida()).isFalse();
            assertThat(r.motivo()).isEqualTo(MotivoRechazo.ROL_SIN_PERMISO);
        }

        @Test
        @DisplayName("El auditor no escribe nada, nunca")
        void auditor_es_solo_lectura() {
            for (EstadoEntrega origen : EstadoEntrega.values()) {
                for (EstadoEntrega destino : EstadoEntrega.values()) {
                    assertThat(MaquinaEstadosEntrega.evaluar(origen, destino, AUDITOR).permitida())
                            .as("auditor %s -> %s", origen, destino)
                            .isFalse();
                }
            }
        }

        @Test
        @DisplayName("El productor si puede registrar una entrega")
        void productor_registra() {
            assertThat(MaquinaEstadosEntrega.puedeRegistrar(CLIENTE)).isTrue();
            assertThat(MaquinaEstadosEntrega.puedeRegistrar(OPERADOR)).isTrue();
            assertThat(MaquinaEstadosEntrega.puedeRegistrar(AUDITOR)).isFalse();
        }

        /**
         * Supuesto 3 de docs/01-maquina-de-estados.md: el enunciado se contradice
         * entre la seccion 3 y la 6. Si la pauta dice que el Admin NO cambia
         * estados, este test es lo unico que hay que cambiar.
         */
        @Test
        @DisplayName("SUPUESTO: el Admin puede todo lo que puede el Jefe de acopio")
        void admin_equivale_a_operador() {
            for (EstadoEntrega origen : EstadoEntrega.values()) {
                for (EstadoEntrega destino : EstadoEntrega.values()) {
                    assertThat(MaquinaEstadosEntrega.evaluar(origen, destino, ADMIN).permitida())
                            .as("%s -> %s", origen, destino)
                            .isEqualTo(MaquinaEstadosEntrega.evaluar(origen, destino, OPERADOR).permitida());
                }
            }
        }
    }

    @Nested
    @DisplayName("Supuestos pendientes de confirmar con la pauta")
    class Supuestos {

        /** Supuesto 1: el enunciado solo dice que la capacidad baja al recibir. */
        @Test
        @DisplayName("SUPUESTO: despachar NO libera la capacidad de bodega")
        void despachar_no_libera_capacidad() {
            var r = MaquinaEstadosEntrega.evaluar(EN_DESPACHO, DESPACHADA, OPERADOR);

            assertThat(r.efectos()).doesNotContain(Efecto.DEVOLVER_CAPACIDAD);
        }

        /** Supuesto 2: una vez cargando el camion, la unica salida es despachar. */
        @Test
        @DisplayName("SUPUESTO: no se puede rechazar una entrega ya en EN_DESPACHO")
        void no_se_rechaza_en_despacho() {
            var r = MaquinaEstadosEntrega.evaluar(EN_DESPACHO, RECHAZADA, OPERADOR);

            assertThat(r.permitida()).isFalse();
        }
    }

    @Nested
    @DisplayName("Casos borde")
    class CasosBorde {

        @Test
        @DisplayName("Quedarse en el mismo estado no es una transicion valida")
        void mismo_estado() {
            assertThat(MaquinaEstadosEntrega.evaluar(RECIBIDA, RECIBIDA, OPERADOR).permitida()).isFalse();
        }

        @Test
        @DisplayName("No se puede retroceder de EN_CLASIFICACION a RECIBIDA")
        void no_retrocede() {
            assertThat(MaquinaEstadosEntrega.evaluar(EN_CLASIFICACION, RECIBIDA, OPERADOR).permitida()).isFalse();
        }

        @Test
        @DisplayName("Una transicion rechazada no dispara ningun efecto")
        void rechazada_no_tiene_efectos() {
            var r = MaquinaEstadosEntrega.evaluar(REGISTRADA, DESPACHADA, OPERADOR);

            assertThat(r.permitida()).isFalse();
            assertThat(r.efectos()).isEmpty();
        }

        @Test
        @DisplayName("Una transicion permitida no trae motivo de rechazo")
        void permitida_no_tiene_motivo() {
            assertThat(MaquinaEstadosEntrega.evaluar(REGISTRADA, RECIBIDA, OPERADOR).motivo()).isNull();
        }
    }
}
