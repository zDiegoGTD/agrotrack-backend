package cl.agrotrack.deliveries.aplicacion;

import cl.agrotrack.deliveries.aplicacion.Dtos.CambioEstadoRequest;
import cl.agrotrack.deliveries.aplicacion.Dtos.EntregaResponse;
import cl.agrotrack.deliveries.aplicacion.Dtos.RegistrarEntregaRequest;
import cl.agrotrack.deliveries.aplicacion.Excepciones.AccesoDenegado;
import cl.agrotrack.deliveries.aplicacion.Excepciones.CapacidadInsuficiente;
import cl.agrotrack.deliveries.aplicacion.Excepciones.TransicionNoPermitida;
import cl.agrotrack.deliveries.dominio.Actor;
import cl.agrotrack.deliveries.dominio.Efecto;
import cl.agrotrack.deliveries.dominio.EstadoEntrega;
import cl.agrotrack.deliveries.dominio.MotivoRechazo;
import cl.agrotrack.deliveries.dominio.Rol;
import cl.agrotrack.deliveries.infraestructura.persistencia.Entrega;
import cl.agrotrack.deliveries.infraestructura.persistencia.EntregaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Orquestacion del caso de uso, con catalog y publicador como dobles. */
@ExtendWith(MockitoExtension.class)
class EntregaServiceTest {

    static final Actor OPERADOR = new Actor("op-1", "Jefa de acopio", Rol.OPERADOR);
    static final Actor PRODUCTOR = new Actor("prod-1", "Productor Uno", Rol.CLIENTE);
    static final Actor OTRO_PRODUCTOR = new Actor("prod-2", "Productor Dos", Rol.CLIENTE);
    static final Actor AUDITOR = new Actor("aud-1", "Auditor", Rol.AUDITOR);
    static final Instant AHORA = Instant.parse("2026-09-07T12:00:00Z");

    @Mock EntregaRepository repo;
    @Mock CatalogClient catalog;
    @Mock PublicadorEventos publicador;

    EntregaService servicio;

    @BeforeEach
    void setUp() {
        servicio = new EntregaService(repo, catalog, publicador, Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    private Entrega registrada() {
        return new Entrega("DEL-2026-000001", "prod-1", 7L, 3L, new BigDecimal("100"));
    }

    private Entrega recibida() {
        Entrega e = registrada();
        e.transicionar(EstadoEntrega.RECIBIDA, new BigDecimal("98"), null, AHORA);
        return e;
    }

    @Test
    @DisplayName("T1: registrar valida contra catalog, guarda REGISTRADA con codigo DEL-AAAA-NNNNNN y publica")
    void registrar() {
        when(repo.siguienteNumeroDeCodigo()).thenReturn(42L);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EntregaResponse r = servicio.registrar(new RegistrarEntregaRequest(7L, 3L, new BigDecimal("100")), PRODUCTOR);

        assertThat(r.codigo()).isEqualTo("DEL-2026-000042");
        assertThat(r.estado()).isEqualTo(EstadoEntrega.REGISTRADA);
        assertThat(r.productorId()).isEqualTo("prod-1");
        verify(catalog).verificarProducto(7L);
        verify(catalog).verificarBodega(3L);
        verify(publicador).entregaRegistrada(any(), eq(PRODUCTOR));
    }

    @Test
    @DisplayName("El auditor no registra: 403 antes de tocar catalog")
    void auditorNoRegistra() {
        assertThatThrownBy(() -> servicio.registrar(new RegistrarEntregaRequest(7L, 3L, BigDecimal.TEN), AUDITOR))
                .isInstanceOf(AccesoDenegado.class);
        verify(catalog, never()).verificarProducto(any());
    }

    @Test
    @DisplayName("T2: recibir reserva en catalog el peso real, guarda y publica los tres efectos")
    void recibir() {
        Entrega e = registrada();
        when(repo.findById(1L)).thenReturn(Optional.of(e));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EntregaResponse r = servicio.cambiarEstado(1L,
                new CambioEstadoRequest("RECIBIDA", new BigDecimal("98"), null), OPERADOR);

        assertThat(r.estado()).isEqualTo(EstadoEntrega.RECIBIDA);
        assertThat(r.pesoRecibido()).isEqualByComparingTo("98");
        assertThat(r.fechaRecepcion()).isEqualTo(AHORA);
        verify(catalog).reservarCapacidad(3L, new BigDecimal("98"), "DEL-2026-000001");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<Efecto>> efectos = ArgumentCaptor.forClass(Set.class);
        verify(publicador).entregaTransicionada(any(), eq(EstadoEntrega.REGISTRADA), efectos.capture(), eq(OPERADOR));
        assertThat(efectos.getValue()).containsExactlyInAnyOrder(
                Efecto.DESCONTAR_CAPACIDAD, Efecto.NOTIFICAR_PRODUCTOR, Efecto.EMITIR_TICKET_BODEGA);
    }

    @Test
    @DisplayName("T2 sin peso informado: se reserva la cantidad declarada")
    void recibirSinPeso() {
        when(repo.findById(1L)).thenReturn(Optional.of(registrada()));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        servicio.cambiarEstado(1L, new CambioEstadoRequest("RECIBIDA", null, null), OPERADOR);

        verify(catalog).reservarCapacidad(3L, new BigDecimal("100"), "DEL-2026-000001");
    }

    @Test
    @DisplayName("Si catalog dice que no hay capacidad, la entrega NO cambia ni se publica nada")
    void sinCapacidadNoPersiste() {
        Entrega e = registrada();
        when(repo.findById(1L)).thenReturn(Optional.of(e));
        doThrow(new CapacidadInsuficiente("Capacidad insuficiente"))
                .when(catalog).reservarCapacidad(any(), any(), any());

        assertThatThrownBy(() -> servicio.cambiarEstado(1L, new CambioEstadoRequest("RECIBIDA", null, null), OPERADOR))
                .isInstanceOf(CapacidadInsuficiente.class);

        assertThat(e.getEstado()).isEqualTo(EstadoEntrega.REGISTRADA);
        verify(repo, never()).save(any());
        verify(publicador, never()).entregaTransicionada(any(), any(), any(), any());
    }

    @Test
    @DisplayName("T7: rechazar una entrega recibida devuelve a catalog lo que ocupaba")
    void rechazarRecibidaLibera() {
        when(repo.findById(1L)).thenReturn(Optional.of(recibida()));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EntregaResponse r = servicio.cambiarEstado(1L,
                new CambioEstadoRequest("RECHAZADA", null, "Humedad fuera de rango"), OPERADOR);

        assertThat(r.estado()).isEqualTo(EstadoEntrega.RECHAZADA);
        assertThat(r.motivoRechazo()).isEqualTo("Humedad fuera de rango");
        verify(catalog).liberarCapacidad(3L, new BigDecimal("98"), "DEL-2026-000001");
    }

    @Test
    @DisplayName("Rechazar sin motivo es un 400")
    void rechazarSinMotivo() {
        when(repo.findById(1L)).thenReturn(Optional.of(registrada()));

        assertThatThrownBy(() -> servicio.cambiarEstado(1L, new CambioEstadoRequest("RECHAZADA", null, " "), OPERADOR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("El estado llega con tilde como en el enunciado y se entiende igual")
    void estadoConTilde() {
        when(repo.findById(1L)).thenReturn(Optional.of(recibida()));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EntregaResponse r = servicio.cambiarEstado(1L, new CambioEstadoRequest("EN_CLASIFICACIÓN", null, null), OPERADOR);

        assertThat(r.estado()).isEqualTo(EstadoEntrega.EN_CLASIFICACION);
    }

    @Test
    @DisplayName("El productor no cambia estados: TransicionNoPermitida con motivo de rol")
    void productorNoCambiaEstado() {
        when(repo.findById(1L)).thenReturn(Optional.of(registrada()));

        assertThatThrownBy(() -> servicio.cambiarEstado(1L, new CambioEstadoRequest("RECIBIDA", null, null), PRODUCTOR))
                .isInstanceOf(TransicionNoPermitida.class)
                .extracting(ex -> ((TransicionNoPermitida) ex).motivo())
                .isEqualTo(MotivoRechazo.ROL_SIN_PERMISO);
        verify(catalog, never()).reservarCapacidad(any(), any(), any());
    }

    @Test
    @DisplayName("Saltarse la recepcion es TransicionNoPermitida por transicion invalida")
    void noSeSaltaLaRecepcion() {
        when(repo.findById(1L)).thenReturn(Optional.of(registrada()));

        assertThatThrownBy(() -> servicio.cambiarEstado(1L, new CambioEstadoRequest("EN_DESPACHO", null, null), OPERADOR))
                .isInstanceOf(TransicionNoPermitida.class)
                .extracting(ex -> ((TransicionNoPermitida) ex).motivo())
                .isEqualTo(MotivoRechazo.TRANSICION_NO_VALIDA);
    }

    @Test
    @DisplayName("Un productor no ve la entrega de otro")
    void productorNoVeAjenas() {
        when(repo.findById(1L)).thenReturn(Optional.of(registrada()));

        assertThatThrownBy(() -> servicio.obtener(1L, OTRO_PRODUCTOR)).isInstanceOf(AccesoDenegado.class);
        assertThat(servicio.obtener(1L, PRODUCTOR).codigo()).isEqualTo("DEL-2026-000001");
        assertThat(servicio.obtener(1L, AUDITOR).codigo()).isEqualTo("DEL-2026-000001");
    }

    @Test
    @DisplayName("Transiciones disponibles: para el operador desde RECIBIDA son clasificar o rechazar")
    void transicionesDisponibles() {
        when(repo.findById(1L)).thenReturn(Optional.of(recibida()));

        var r = servicio.transicionesDisponibles(1L, OPERADOR);

        assertThat(r.actual()).isEqualTo(EstadoEntrega.RECIBIDA);
        assertThat(r.permitidas()).containsExactlyInAnyOrder(EstadoEntrega.EN_CLASIFICACION, EstadoEntrega.RECHAZADA);
        assertThat(servicio.transicionesDisponibles(1L, PRODUCTOR).permitidas()).isEmpty();
    }
}
