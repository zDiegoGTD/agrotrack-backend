package cl.agrotrack.users.dominio;

/** Por que no procede un cambio de estado. Viaja como "codigo" en el problem+json. */
public enum MotivoRechazoCambio {
    TRANSICION_NO_PERMITIDA,
    MOTIVO_OBLIGATORIO,
    AUTO_DESACTIVACION
}
