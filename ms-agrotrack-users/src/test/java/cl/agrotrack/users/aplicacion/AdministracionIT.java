package cl.agrotrack.users.aplicacion;

import cl.agrotrack.users.aplicacion.Dtos.CambioEstadoRequest;
import cl.agrotrack.users.aplicacion.Dtos.PerfilRequest;
import cl.agrotrack.users.dominio.CambioEstadoInvalidoException;
import cl.agrotrack.users.dominio.MotivoRechazoCambio;
import cl.agrotrack.users.soporte.PostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;

import static cl.agrotrack.users.dominio.EstadoUsuario.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdministracionIT extends PostgresIT {

    @Autowired UsuarioService servicio;
    @Autowired JdbcTemplate jdbc;

    IdentidadToken admin;
    IdentidadToken productor;
    Long idAdmin;
    Long idProductor;

    @BeforeEach
    void preparar() {
        jdbc.update("DELETE FROM perfil_productor");
        jdbc.update("DELETE FROM usuario");
        admin = new IdentidadToken("admin-oid", "admin@agrotrack.cl", "Admin", List.of("ADMIN"));
        productor = new IdentidadToken("prod-oid", "prod@agrotrack.cl", "Productor", List.of("CLIENTE"));
        idAdmin = servicio.sincronizar(admin).id();          // primer admin: ACTIVO
        idProductor = servicio.sincronizar(productor).id();  // PENDIENTE
    }

    @Test
    @DisplayName("Aprobar deja quien y cuando")
    void aprobar() {
        var r = servicio.cambiarEstado(idProductor, new CambioEstadoRequest(ACTIVO, null), "admin-oid");

        assertThat(r.estado()).isEqualTo(ACTIVO);
        assertThat(r.aprobadoPor()).isEqualTo("admin-oid");
        assertThat(r.aprobadoEn()).isNotNull();
    }

    @Test
    @DisplayName("Rechazar guarda el motivo; reconsiderar lo limpia")
    void rechazarYReconsiderar() {
        var rechazado = servicio.cambiarEstado(idProductor, new CambioEstadoRequest(RECHAZADO, "  RUT no coincide "), "admin-oid");
        assertThat(rechazado.motivo()).isEqualTo("RUT no coincide");

        var reconsiderado = servicio.cambiarEstado(idProductor, new CambioEstadoRequest(ACTIVO, null), "admin-oid");
        assertThat(reconsiderado.motivo()).isNull();
    }

    @Test
    @DisplayName("El admin no puede desactivarse a si mismo")
    void autoDesactivacion() {
        assertThatThrownBy(() -> servicio.cambiarEstado(idAdmin, new CambioEstadoRequest(INACTIVO, "prueba"), "admin-oid"))
                .isInstanceOfSatisfying(CambioEstadoInvalidoException.class,
                        e -> assertThat(e.motivo()).isEqualTo(MotivoRechazoCambio.AUTO_DESACTIVACION));
        assertThat(servicio.obtener(idAdmin).estado()).isEqualTo(ACTIVO);
    }

    @Test
    @DisplayName("Listar filtra por estado; sin filtro trae a todos")
    void listar() {
        assertThat(servicio.listar(PENDIENTE)).extracting(Dtos.UsuarioResponse::azureOid).containsExactly("prod-oid");
        assertThat(servicio.listar(null)).hasSize(2);
    }

    @Test
    @DisplayName("me de alguien que nunca entro: no existe")
    void meInexistente() {
        assertThatThrownBy(() -> servicio.me("nadie")).isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    @DisplayName("El productor edita su propia ficha y aparece en su detalle")
    void fichaPropia() {
        servicio.actualizarPerfil(idProductor, new PerfilRequest("12345678-k", "Agricola Sur", "+56911112222", "Talca", 3L), productor);

        var yo = servicio.me("prod-oid");
        assertThat(yo.perfil().rut()).isEqualTo("12345678-K");
        assertThat(yo.perfil().bodegaHabitualId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("Un productor no edita la ficha de otro")
    void fichaAjena() {
        var otro = new IdentidadToken("otro-oid", "o@agrotrack.cl", "Otro", List.of("CLIENTE"));
        servicio.sincronizar(otro);

        assertThatThrownBy(() -> servicio.actualizarPerfil(idProductor,
                new PerfilRequest("12345678-9", "X", null, null, null), otro))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("El admin edita cualquier ficha; un RUT ya usado por otro se rechaza")
    void rutDuplicado() {
        servicio.actualizarPerfil(idProductor, new PerfilRequest("12345678-9", "Agricola Sur", null, null, null), admin);

        assertThatThrownBy(() -> servicio.actualizarPerfil(idAdmin,
                new PerfilRequest("12345678-9", "Otra", null, null, null), admin))
                .isInstanceOf(RutDuplicadoException.class);
    }

    @Test
    @DisplayName("Guardar dos veces la misma ficha no choca con su propio RUT")
    void mismaFichaDosVeces() {
        servicio.actualizarPerfil(idProductor, new PerfilRequest("12345678-9", "Agricola Sur", null, null, null), productor);
        var r = servicio.actualizarPerfil(idProductor, new PerfilRequest("12345678-9", "Agricola Sur SpA", null, null, null), productor);
        assertThat(r.razonSocial()).isEqualTo("Agricola Sur SpA");
    }
}
