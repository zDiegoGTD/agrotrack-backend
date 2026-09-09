// Emite un JWT RS256 para probar los servicios en perfil "local", con la
// misma forma que el token de Azure AD que llegara en produccion.
//
//   node mint.mjs OPERADOR                  -> token para el jefe de acopio
//   node mint.mjs CLIENTE productor-01      -> token para un productor concreto
//   node mint.mjs ADMIN,AUDITOR             -> varios roles
//
// Claims que los servicios leen: roles (autorizacion), oid (identidad del
// productor), name (para mostrar), aud/iss (validacion).
import { createSign, randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const aqui = dirname(fileURLToPath(import.meta.url));
const privateKey = readFileSync(join(aqui, "local-private.pem"), "utf8");

const roles = (process.argv[2] ?? "OPERADOR").split(",").map((r) => r.trim().toUpperCase());
const oid = process.argv[3] ?? `${roles[0].toLowerCase()}-local`;

const b64url = (obj) =>
  Buffer.from(JSON.stringify(obj)).toString("base64url");

const ahora = Math.floor(Date.now() / 1000);
const header = { alg: "RS256", typ: "JWT", kid: "local-dev" };
const payload = {
  iss: "http://localhost/local-issuer",
  aud: "api://agrotrack-local",
  sub: oid,
  oid,
  name: `Usuario ${roles.join("/")} (local)`,
  preferred_username: `${oid}@agrotrack.local`,
  roles,
  iat: ahora,
  nbf: ahora,
  exp: ahora + 8 * 3600,
  jti: randomUUID(),
};

const sinFirma = `${b64url(header)}.${b64url(payload)}`;
const firma = createSign("RSA-SHA256").update(sinFirma).sign(privateKey, "base64url");
process.stdout.write(`${sinFirma}.${firma}\n`);
