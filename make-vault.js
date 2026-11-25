// Seals the lane scripts into opaque assets for the APK.
//
// Run:  ZAPP_LANE_PEPPER=<secret> node make-vault.js
//
// Reads  reference/lane/serve.sh        -> assets/lane/serve.bin
//        reference/lane/zapp_proxy.py   -> assets/lane/proxy.bin
//
// The key is SHA-256(pepper | "com.zapp.net" | "lane-v1"). The pepper is NEVER
// stored in this file or anywhere on disk: it is supplied through the
// environment, and this script prints it only as an XOR'd byte array for
// Vault.kt, which reassembles it at runtime.
//
// The plaintext lives in reference/, which .gitignore keeps out of the repo, so
// only the sealed .bin blobs and this sealer are ever published.

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PEPPER = process.env.ZAPP_LANE_PEPPER;
if (!PEPPER || PEPPER.length < 16) {
  console.error('ZAPP_LANE_PEPPER is required (16+ chars). It is never stored in this file.');
  process.exit(1);
}
const XOR = 0x5a;
const SALT = '|com.zapp.net|lane-v1';

const root = path.resolve(__dirname);
const assets = path.join(root, 'app', 'src', 'main', 'assets');
const srcDir = path.join(root, 'reference', 'lane');
const outDir = path.join(assets, 'lane');

const JOBS = [
  { src: 'serve.sh', out: 'serve.bin' },
  { src: 'zapp_proxy.py', out: 'proxy.bin' },
];

function key() {
  return crypto.createHash('sha256').update(Buffer.from(PEPPER + SALT, 'utf8')).digest();
}

fs.mkdirSync(outDir, { recursive: true });

const k = key();
console.log('key sha256(hex) =', k.toString('hex'));

for (const job of JOBS) {
  const srcPath = path.join(srcDir, job.src);
  if (!fs.existsSync(srcPath)) {
    console.log('MISSING', srcPath);
    continue;
  }
  const plain = fs.readFileSync(srcPath);
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', k, iv);
  const body = Buffer.concat([cipher.update(plain), cipher.final()]);
  const tag = cipher.getAuthTag();
  // Layout: iv(12) | tag(16) | ciphertext
  const blob = Buffer.concat([iv, tag, body]);
  const outPath = path.join(outDir, job.out);
  fs.writeFileSync(outPath, blob);
  console.log(
    `${job.src} ${plain.length} B -> ${job.out} ${blob.length} B  ` +
      `(+${blob.length - plain.length} B overhead)`
  );

  // Round-trip proof: decrypt exactly the way Vault.kt does and compare bytes.
  const back = fs.readFileSync(outPath);
  const d = crypto.createDecipheriv('aes-256-gcm', k, back.subarray(0, 12));
  d.setAuthTag(back.subarray(12, 28));
  const out = Buffer.concat([d.update(back.subarray(28)), d.final()]);
  console.log('  round-trip:', out.equals(plain) ? 'IDENTICAL' : 'MISMATCH');
}

// The pepper as Vault.kt will hold it.
const xored = Array.from(Buffer.from(PEPPER, 'utf8')).map((b) => b ^ XOR);
const lines = [];
for (let i = 0; i < xored.length; i += 12) {
  lines.push('        ' + xored.slice(i, i + 12).map((n) => '0x' + n.toString(16).padStart(2, '0')).join(', ') + ',');
}
console.log('\n--- paste into Vault.kt ---');
console.log('private val P = intArrayOf(');
console.log(lines.join('\n'));
console.log(')');
console.log('private const val X = 0x' + XOR.toString(16));
