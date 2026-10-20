// Genera el par de claves RSA para firmar JWT en DESARROLLO LOCAL.
//   node generar-claves.mjs           crea el par si no existe
//   node generar-claves.mjs --rotar   reemplaza el par actual por uno nuevo
//
// La clave privada queda solo en este PC (infra/local/jwt/local-private.pem,
// ignorada por git) y la publica se copia a src/main/resources/jwt/local-public.pem
// de cada servicio, que si se versiona. En AWS no se usa nada de esto: alli firma
// Azure AD y los servicios corren con el perfil "aws", que no conoce esta clave.
import { generateKeyPairSync } from "node:crypto";
import { writeFileSync, existsSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const aqui = dirname(fileURLToPath(import.meta.url));
const raiz = join(aqui, "..", "..", "..");
const priv = join(aqui, "local-private.pem");
const pub = join(aqui, "local-public.pem");
const rotar = process.argv.includes("--rotar");

if (existsSync(priv) && !rotar) {
  console.log("Ya existe local-private.pem; no se regenera para no invalidar tokens (usa --rotar para cambiarla).");
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

// La publica va a cada servicio que valida tokens locales
for (const dir of readdirSync(raiz).filter((d) => d.startsWith("ms-agrotrack-"))) {
  const destino = join(raiz, dir, "src", "main", "resources", "jwt", "local-public.pem");
  if (existsSync(destino)) {
    writeFileSync(destino, publicKey);
    console.log("  publica -> " + destino);
  }
}
console.log("Recompila los servicios (mvnw package) para que tomen la clave publica nueva.");
