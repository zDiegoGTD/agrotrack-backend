package cl.agrotrack.users.dominio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static cl.agrotrack.users.dominio.EstadoUsuario.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Especificacion ejecutable del ciclo de vida de una cuenta. Sin Spring ni base. */
class MaquinaEstadosUsuarioTest {

    @Nested
    @DisplayName("Estado al primer ingreso")
    class EstadoInicial {

        @Test
        @DisplayName("Sistema sin activos y token ADMIN: nace ACTIVO (primer admin)")
        void primerAdmin() {
            assertThat(MaquinaEstadosUsuario.estadoInicial(true, false)).isEqualTo(ACTIVO);
        }

        @Test
        @DisplayName("Ya hay un activo: el segundo admin nace PENDIENTE")
        void segundoAdmin() {
            assertThat(MaquinaEstadosUsuario.estadoInicial(true, true)).isEqualTo(PENDIENTE);
        }

        @Test
        @DisplayName("Sin ADMIN en el token nunca se auto-aprueba, aunque el sistema este vacio")
        void noAdminVacio() {
            assertThat(MaquinaEstadosUsuario.estadoInicial(false, false)).isEqualTo(PENDIENTE);
        }
    }

    @Nested
    @DisplayName("Cambios permitidos")
    class Permitidos {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({"PENDIENTE,ACTIVO", "INACTIVO,ACTIVO", "RECHAZADO,ACTIVO"})
        @DisplayName("Aprobar, reactivar y reconsiderar no exigen motivo")
        void sinMotivo(EstadoUsuario desde, EstadoUsuario hacia) {
            assertThatCode(() -> MaquinaEstadosUsuario.validarCambio(desde, hacia, null, false)).doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({"PENDIENTE,RECHAZADO", "ACTIVO,INACTIVO"})
        @DisplayName("Rechazar y desactivar con motivo")
        void conMotivo(EstadoUsuario desde, EstadoUsuario hacia) {
            assertThatCode(() -> MaquinaEstadosUsuario.validarCambio(desde, hacia, "no es productor", false)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Cambios rechazados")
    class Rechazados {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({"PENDIENTE,INACTIVO", "ACTIVO,PENDIENTE", "ACTIVO,RECHAZADO", "RECHAZADO,INACTIVO",
                "INACTIVO,RECHAZADO", "ACTIVO,ACTIVO", "PENDIENTE,PENDIENTE"})
        @DisplayName("Transiciones fuera de la tabla")
        void fueraDeTabla(EstadoUsuario desde, EstadoUsuario hacia) {
            assertThatThrownBy(() -> MaquinaEstadosUsuario.validarCambio(desde, hacia, "motivo", false))
                    .isInstanceOfSatisfying(CambioEstadoInvalidoException.class,
                            e -> assertThat(e.motivo()).isEqualTo(MotivoRechazoCambio.TRANSICION_NO_PERMITIDA));
        }

        @ParameterizedTest(name = "motivo=''{0}''")
        @CsvSource(value = {"NULL", "''", "'   '"}, nullValues = "NULL")
        @DisplayName("Rechazar sin motivo")
        void rechazarSinMotivo(String motivo) {
            assertThatThrownBy(() -> MaquinaEstadosUsuario.validarCambio(PENDIENTE, RECHAZADO, motivo, false))
                    .isInstanceOfSatisfying(CambioEstadoInvalidoException.class,
                            e -> assertThat(e.motivo()).isEqualTo(MotivoRechazoCambio.MOTIVO_OBLIGATORIO));
        }

        @Test
        @DisplayName("Un admin no puede desactivarse a si mismo, aunque de motivo")
        void autoDesactivacion() {
            assertThatThrownBy(() -> MaquinaEstadosUsuario.validarCambio(ACTIVO, INACTIVO, "me voy", true))
                    .isInstanceOfSatisfying(CambioEstadoInvalidoException.class,
                            e -> assertThat(e.motivo()).isEqualTo(MotivoRechazoCambio.AUTO_DESACTIVACION));
        }
    }
}
