// Genera el par de claves RSA para firmar JWT en DESARROLLO LOCAL.
// Se ejecuta una sola vez:  node generar-claves.mjs
//
// La clave privada queda aqui (infra/local/jwt/local-private.pem) y la
// publica se copia a src/main/resources/jwt/local-public.pem de cada
// servicio. En AWS no se usa nada de esto: alli firma Azure AD.
import { generateKeyPairSync } from "node:crypto";
import { writeFileSync, existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const aqui = dirname(fileURLToPath(import.meta.url));
const priv = join(aqui, "local-private.pem");
const pub = join(aqui, "local-public.pem");

if (existsSync(priv)) {
  console.log("Ya existe local-private.pem; no se regenera para no invalidar tokens.");
  process.exit(0);
}

const { publicKey, privateKey } = generateKeyPairSync("rsa", {
  modulusLength: 2048,
  publicKeyEncoding: { type: "spki", format: "pem" },
  privateKeyEncoding: { type: "pkcs8", format: "pem" },
});

writeFileSync(priv, privateKey);
writeFileSync(pub, publicKey);
console.log("Claves generadas:\n  " + priv + "\n  " + pub);
