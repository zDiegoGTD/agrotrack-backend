package cl.agrotrack.catalog.infraestructura.web;

import cl.agrotrack.catalog.aplicacion.CapacidadService;
import cl.agrotrack.catalog.aplicacion.CatalogService;
import cl.agrotrack.catalog.aplicacion.Dtos.ProductoResponse;
import cl.agrotrack.catalog.aplicacion.RecursoNoEncontradoException;
import cl.agrotrack.catalog.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Autorizacion por rol y validacion, sin base de datos. */
@WebMvcTest({ProductoController.class, BodegaController.class})
@Import({SecurityConfig.class, ApiExceptionHandler.class})
class ProductoControllerTest {

    private static final String PRODUCTO_OK = """
            {"codigo":"TRIGO-01","nombre":"Trigo candeal","unidadMedida":"KG","tarifa":120.5}
            """;

    @Autowired MockMvc mvc;
    @MockitoBean CatalogService catalog;
    @MockitoBean CapacidadService capacidad;

    @Test
    @DisplayName("Sin token: 401")
    void sinToken() throws Exception {
        mvc.perform(get("/api/catalog/productos")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("El productor puede leer el catalogo")
    void clienteLee() throws Exception {
        mvc.perform(get("/api/catalog/productos")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("El productor NO puede crear productos: 403")
    void clienteNoCrea() throws Exception {
        mvc.perform(post("/api/catalog/productos").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(PRODUCTO_OK))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "OPERADOR")
    @DisplayName("El jefe de acopio tampoco crea productos: 403")
    void operadorNoCrea() throws Exception {
        mvc.perform(post("/api/catalog/productos").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(PRODUCTO_OK))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("El admin crea: 201 con el recurso")
    void adminCrea() throws Exception {
        when(catalog.crearProducto(any())).thenReturn(
                new ProductoResponse(1L, "TRIGO-01", "Trigo candeal", "KG", new BigDecimal("120.5"), true));

        mvc.perform(post("/api/catalog/productos").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(PRODUCTO_OK))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.codigo").value("TRIGO-01"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Alias del enunciado: /api/catalog/services responde igual")
    void aliasServices() throws Exception {
        mvc.perform(get("/api/catalog/services")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Body invalido: 400 con los campos que fallaron")
    void validacion() throws Exception {
        mvc.perform(post("/api/catalog/productos").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigo\":\"\",\"tarifa\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.codigo").exists())
                .andExpect(jsonPath("$.campos.tarifa").exists())
                .andExpect(jsonPath("$.campos.nombre").exists());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Recurso inexistente: 404 como problem+json")
    void noEncontrado() throws Exception {
        when(catalog.obtenerProducto(99L)).thenThrow(new RecursoNoEncontradoException("Producto", 99L));

        mvc.perform(get("/api/catalog/productos/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Producto 99 no existe"));
    }

    @Test
    @WithMockUser(roles = "CLIENTE")
    @DisplayName("Reservar capacidad es de operador/admin, no del productor")
    void clienteNoReserva() throws Exception {
        mvc.perform(post("/api/catalog/bodegas/1/capacidad/reservar").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cantidad\":10,\"entregaCodigo\":\"DEL-1\"}"))
                .andExpect(status().isForbidden());
    }
}
