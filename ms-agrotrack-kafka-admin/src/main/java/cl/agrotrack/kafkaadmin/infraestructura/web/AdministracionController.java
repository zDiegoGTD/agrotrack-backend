package cl.agrotrack.kafkaadmin.infraestructura.web;

import cl.agrotrack.kafkaadmin.aplicacion.KafkaAdminService;
import cl.agrotrack.kafkaadmin.aplicacion.KafkaAdminService.EstadoGrupo;
import cl.agrotrack.kafkaadmin.aplicacion.KafkaAdminService.EstadoTopico;
import cl.agrotrack.kafkaadmin.infraestructura.web.dto.AdminDtos;
import cl.agrotrack.kafkaadmin.infraestructura.web.dto.AdminDtos.CambioConfiguracion;
import cl.agrotrack.kafkaadmin.infraestructura.web.dto.AdminDtos.CambioParticiones;
import cl.agrotrack.kafkaadmin.infraestructura.web.dto.AdminDtos.ReinicioOffsets;
import cl.agrotrack.kafkaadmin.infraestructura.web.dto.AdminDtos.TopicoNuevo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Crear, modificar y eliminar topicos; inspeccionar consumer groups y mover
 * sus offsets. Escribir es solo ADMIN; leer, ADMIN y OPERADOR.
 */
@RestController
@RequestMapping("/api/kafka")
@Validated
public class AdministracionController {

    private final KafkaAdminService servicio;

    public AdministracionController(KafkaAdminService servicio) {
        this.servicio = servicio;
    }

    // ---- topicos ----

    @PostMapping("/topics")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<EstadoTopico> crear(@Valid @RequestBody TopicoNuevo pedido) {
        EstadoTopico creado = servicio.crearTopico(pedido.nombre(), pedido.particiones(), pedido.replicas(),
                pedido.configuracion() == null ? Map.of() : pedido.configuracion());
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{nombre}").buildAndExpand(creado.nombre()).toUri())
                .body(creado);
    }

    @GetMapping("/topics/{nombre}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public EstadoTopico topico(@PathVariable @Pattern(regexp = AdminDtos.NOMBRE) String nombre) {
        return servicio.topico(nombre);
    }

    @DeleteMapping("/topics/{nombre}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> eliminar(@PathVariable @Pattern(regexp = AdminDtos.NOMBRE) String nombre) {
        servicio.eliminarTopico(nombre);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/topics/{nombre}/config")
    @PreAuthorize("hasRole('ADMIN')")
    public EstadoTopico cambiarConfiguracion(@PathVariable @Pattern(regexp = AdminDtos.NOMBRE) String nombre,
                                             @Valid @RequestBody CambioConfiguracion pedido) {
        return servicio.cambiarConfiguracion(nombre, pedido.configuracion());
    }

    @PostMapping("/topics/{nombre}/particiones")
    @PreAuthorize("hasRole('ADMIN')")
    public EstadoTopico aumentarParticiones(@PathVariable @Pattern(regexp = AdminDtos.NOMBRE) String nombre,
                                            @Valid @RequestBody CambioParticiones pedido) {
        return servicio.aumentarParticiones(nombre, pedido.particiones());
    }

    @GetMapping("/protegidos")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public Set<String> protegidos() {
        return servicio.recursosDelSistema();
    }

    // ---- consumer groups ----

    @GetMapping("/consumer-groups")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public List<EstadoGrupo> grupos() {
        return servicio.grupos();
    }

    @GetMapping("/consumer-groups/{grupo}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public EstadoGrupo grupo(@PathVariable @Pattern(regexp = AdminDtos.NOMBRE) String grupo) {
        return servicio.grupo(grupo);
    }

    @PostMapping("/consumer-groups/{grupo}/topics/{topico}/offsets")
    @PreAuthorize("hasRole('ADMIN')")
    public EstadoGrupo reiniciarOffsets(@PathVariable @Pattern(regexp = AdminDtos.NOMBRE) String grupo,
                                        @PathVariable @Pattern(regexp = AdminDtos.NOMBRE) String topico,
                                        @Valid @RequestBody ReinicioOffsets pedido) {
        return servicio.reiniciarOffsets(grupo, topico, pedido.posicion());
    }
}
