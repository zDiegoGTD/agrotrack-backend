package cl.agrotrack.bff.config;

import cl.agrotrack.bff.cuenta.CuentaUsuario;
import cl.agrotrack.bff.cuenta.EstadoCuentas;
import cl.agrotrack.bff.infraestructura.web.MeController;
import cl.agrotrack.bff.infraestructura.web.ProxyController;
import cl.agrotrack.bff.infraestructura.web.Reenviador;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El indicador del 40% de la pauta, con tokens de verdad: firmados, con los
 * claims que emite Azure AD y validados por la MISMA configuracion que usa el
 * perfil aws (issuer-uri + audiences + scope requerido).
 *
 * <p>No se usa el post-processor jwt() de spring-security-test porque ese se
 * salta la decodificacion: aqui cada token pasa por la verificacion de firma,
 * emisor, audiencia y vigencia. Un servidor HTTP local hace de Azure AD
 * sirviendo el documento de descubrimiento OIDC y las llaves publicas (JWKS).
 */
@WebMvcTest({ProxyController.class, MeController.class, ProxyController.Servicios.class})
@Import({SecurityConfig.class, ValidacionTokenConfig.class})
@TestPropertySource(properties = {
        // Anula la llave local del perfil por defecto: aqui manda issuer-uri, como en AWS
        "spring.security.oauth2.resourceserver.jwt.public-key-location=",
        "spring.security.oauth2.resourceserver.jwt.audiences=api://cliente-prueba,cliente-prueba",
        "agrotrack.seguridad.scope-requerido=access_as_user"
})
class ValidacionJwtTest {

    private static final RSAKey LLAVE_AZURE = nuevaLlave("azure-prueba");
    /** Mismo kid que la llave real: el decodificador la busca, pero la firma no calza. */
    private static final RSAKey LLAVE_FALSA = nuevaLlave("azure-prueba");
    private static final String EMISOR;

    static {
        try {
            HttpServer idp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            String base = "http://127.0.0.1:" + idp.getAddress().getPort() + "/tenant-prueba";
            EMISOR = base + "/v2.0";
            idp.createContext("/tenant-prueba/v2.0/.well-known/openid-configuration", ex -> responder(ex,
                    "{\"issuer\":\"" + EMISOR + "\",\"jwks_uri\":\"" + base + "/discovery/v2.0/keys\","
                            + "\"subject_types_supported\":[\"pairwise\"],\"id_token_signing_alg_values_supported\":[\"RS256\"]}"));
            idp.createContext("/tenant-prueba/discovery/v2.0/keys", ex -> responder(ex, new JWKSet(LLAVE_AZURE.toPublicJWK()).toString()));
            idp.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void emisor(DynamicPropertyRegistry registro) {
        registro.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> EMISOR);
    }

    @Autowired MockMvc mvc;
    @MockitoBean Reenviador reenviador;
    @MockitoBean EstadoCuentas cuentas;

    @BeforeEach
    void cuentaActivaYServicioQueResponde() {
        CuentaUsuario activa = new CuentaUsuario(1L, "ACTIVO", null, null);
        when(cuentas.consultar(any(), any())).thenReturn(activa);
        when(cuentas.sincronizar(any(), any())).thenReturn(activa);
        when(reenviador.reenviar(any(), any(), any(), any()))
                .thenReturn(ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("{\"ok\":true}".getBytes()));
    }

    // ---------------- aceptadas ----------------

    @Test
    @DisplayName("Token valido de Azure: 200")
    void valido() throws Exception {
        pedir("/api/me", token(c -> { })).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Audiencia en formato v1 (api://<clientId>) tambien se acepta")
    void audienciaV1() throws Exception {
        pedir("/api/me", token(c -> c.audience("api://cliente-prueba"))).andExpect(status().isOk());
    }

    // ---------------- 401: el token no sirve ----------------

    @Test
    @DisplayName("Sin token: 401 TOKEN_AUSENTE con WWW-Authenticate y problem+json")
    void sinToken() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.codigo").value("TOKEN_AUSENTE"));
    }

    @Test
    @DisplayName("Vencido (exp en el pasado): 401 TOKEN_VENCIDO")
    void vencido() throws Exception {
        Instant hace = Instant.now().minusSeconds(3600);
        rechazado(token(c -> c.issueTime(Date.from(hace)).notBeforeTime(Date.from(hace))
                .expirationTime(Date.from(hace.plusSeconds(600)))), "TOKEN_VENCIDO");
    }

    @Test
    @DisplayName("Aun no vigente (nbf en el futuro): 401 TOKEN_AUN_NO_VALIDO")
    void aunNoVigente() throws Exception {
        rechazado(token(c -> c.notBeforeTime(Date.from(Instant.now().plusSeconds(1800)))), "TOKEN_AUN_NO_VALIDO");
    }

    @Test
    @DisplayName("Emitido para otra API (aud ajena): 401 AUDIENCIA_INVALIDA")
    void audienciaAjena() throws Exception {
        rechazado(token(c -> c.audience("api://otra-aplicacion")), "AUDIENCIA_INVALIDA");
    }

    @Test
    @DisplayName("Emitido por otro tenant (iss ajeno): 401 EMISOR_INVALIDO")
    void emisorAjeno() throws Exception {
        rechazado(token(c -> c.issuer("https://login.microsoftonline.com/otro-tenant/v2.0")), "EMISOR_INVALIDO");
    }

    @Test
    @DisplayName("Firmado con una llave que no es la de Azure: 401 FIRMA_INVALIDA")
    void firmaFalsa() throws Exception {
        rechazado(token(c -> { }, LLAVE_FALSA), "FIRMA_INVALIDA");
    }

    @Test
    @DisplayName("Token real al que se le cambio el rol a mano: 401 FIRMA_INVALIDA")
    void manipulado() throws Exception {
        String[] partes = token(c -> c.claim("roles", List.of("CLIENTE"))).split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(partes[1]), StandardCharsets.UTF_8)
                .replace("\"CLIENTE\"", "\"ADMIN\"");
        String alterado = partes[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + partes[2];

        rechazado(alterado, "FIRMA_INVALIDA");
    }

    @Test
    @DisplayName("Texto que no es un JWT: 401 TOKEN_INVALIDO")
    void basura() throws Exception {
        rechazado("esto-no-es-un-jwt", "TOKEN_INVALIDO");
    }

    @Test
    @DisplayName("Sin el scope access_as_user: 401 SCOPE_INSUFICIENTE")
    void sinScope() throws Exception {
        rechazado(token(c -> c.claim("scp", "User.Read")), "SCOPE_INSUFICIENTE");
    }

    // ---------------- 403: token valido, rol insuficiente ----------------

    @Test
    @DisplayName("Token valido pero rol sin permiso: 403 ROL_INSUFICIENTE en problem+json")
    void rolInsuficiente() throws Exception {
        pedir("/api/report/kpis", token(c -> c.claim("roles", List.of("CLIENTE"))))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.codigo").value("ROL_INSUFICIENTE"))
                .andExpect(jsonPath("$.status").value(403));
    }

    // ---------------- soporte ----------------

    private ResultActions pedir(String ruta, String token) throws Exception {
        return mvc.perform(get(ruta).header("Authorization", "Bearer " + token));
    }

    private void rechazado(String token, String codigo) throws Exception {
        pedir("/api/me", token)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.containsString("invalid_token")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.codigo").value(codigo));
    }

    private static String token(Consumer<JWTClaimsSet.Builder> ajuste) throws Exception {
        return token(ajuste, LLAVE_AZURE);
    }

    /** Un access token con la forma de los de Azure AD v2. */
    private static String token(Consumer<JWTClaimsSet.Builder> ajuste, RSAKey llave) throws Exception {
        Instant ahora = Instant.now();
        JWTClaimsSet.Builder c = new JWTClaimsSet.Builder()
                .issuer(EMISOR)
                .audience("cliente-prueba")
                .subject("u-1")
                .claim("oid", "u-1")
                .claim("name", "Admin Prueba")
                .claim("preferred_username", "admin@prueba.cl")
                .claim("ver", "2.0")
                .claim("roles", List.of("ADMIN"))
                .claim("scp", "access_as_user")
                .issueTime(Date.from(ahora))
                .notBeforeTime(Date.from(ahora))
                .expirationTime(Date.from(ahora.plusSeconds(3600)));
        ajuste.accept(c);
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(llave.getKeyID()).build(), c.build());
        jwt.sign(new RSASSASigner(llave));
        return jwt.serialize();
    }

    private static RSAKey nuevaLlave(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void responder(HttpExchange ex, String json) throws IOException {
        byte[] cuerpo = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, cuerpo.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(cuerpo);
        }
    }
}
