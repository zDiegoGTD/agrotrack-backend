package cl.agrotrack.bff.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.util.Locale;

/**
 * 401 y 403 del BFF con cuerpo problem+json y un codigo que dice POR QUE se
 * rechazo, ademas de la cabecera WWW-Authenticate estandar (RFC 6750). Sin
 * esto Spring responde solo el codigo y la cabecera: el cliente no sabe si
 * el token vencio, era de otra API o le falta el rol.
 */
public final class RespuestasSeguridad {

    private RespuestasSeguridad() {
    }

    public static AuthenticationEntryPoint noAutenticado(ObjectMapper json) {
        BearerTokenAuthenticationEntryPoint estandar = new BearerTokenAuthenticationEntryPoint();
        return (request, response, ex) -> {
            estandar.commence(request, response, ex);
            Motivo m = ex instanceof InvalidBearerTokenException ? motivoDe(ex.getMessage()) : Motivo.AUSENTE;
            escribir(json, response, HttpStatus.UNAUTHORIZED, m.codigo, m.mensaje);
        };
    }

    public static AccessDeniedHandler sinPermiso(ObjectMapper json) {
        BearerTokenAccessDeniedHandler estandar = new BearerTokenAccessDeniedHandler();
        return (request, response, ex) -> {
            estandar.handle(request, response, ex);
            escribir(json, response, HttpStatus.FORBIDDEN, "ROL_INSUFICIENTE",
                    "Tu rol no permite esta acción.");
        };
    }

    enum Motivo {
        AUSENTE("TOKEN_AUSENTE", "Falta el token Bearer en la cabecera Authorization."),
        VENCIDO("TOKEN_VENCIDO", "El token venció. Vuelve a iniciar sesión."),
        AUN_NO_VALIDO("TOKEN_AUN_NO_VALIDO", "El token todavía no es válido (nbf en el futuro)."),
        AUDIENCIA("AUDIENCIA_INVALIDA", "El token no fue emitido para esta API (audiencia inválida)."),
        EMISOR("EMISOR_INVALIDO", "El token no fue emitido por el tenant de AgroTrack (emisor inválido)."),
        FIRMA("FIRMA_INVALIDA", "La firma del token no es válida: fue alterado o no lo firmó Azure AD."),
        SCOPE("SCOPE_INSUFICIENTE", "El token no trae el scope requerido para usar esta API."),
        INVALIDO("TOKEN_INVALIDO", "El token no tiene un formato válido.");

        final String codigo;
        final String mensaje;

        Motivo(String codigo, String mensaje) {
            this.codigo = codigo;
            this.mensaje = mensaje;
        }
    }

    /** Traduce el mensaje de Spring/Nimbus al motivo. Paquete: lo prueba ValidacionJwtTest. */
    static Motivo motivoDe(String mensaje) {
        String m = mensaje == null ? "" : mensaje.toLowerCase(Locale.ROOT);
        if (m.contains("expired")) return Motivo.VENCIDO;
        if (m.contains("used before")) return Motivo.AUN_NO_VALIDO;
        if (m.contains("aud claim")) return Motivo.AUDIENCIA;
        if (m.contains("iss claim")) return Motivo.EMISOR;
        if (m.contains("scope")) return Motivo.SCOPE;
        if (m.contains("signature") || m.contains("matching key")) return Motivo.FIRMA;
        return Motivo.INVALIDO;
    }

    private static void escribir(ObjectMapper json, HttpServletResponse response, HttpStatus status,
                                 String codigo, String detalle) throws IOException {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detalle);
        p.setTitle(status.getReasonPhrase());
        p.setProperty("codigo", codigo);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), p);
    }
}
