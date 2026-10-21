// Открывает копию, сделанную Android, кодом сайта (как app.js restoreBackup): node web_open.js <web-dir> <in.json> <пароль>
const B = require(require('path').resolve(process.argv[2], 'backup.js'));
(async () => {
  const f = JSON.parse(require('fs').readFileSync(process.argv[3], 'utf8'));
  const key = await B.deriveKey(process.argv[4], f.row.salt, f.row.kdf);
  const o = await B.open(B.b64d(f.bin), key, f.row.user_id);
  console.log(JSON.stringify(o.messages.map(B.toCache)));
})().catch(e => { console.error('ERR', e.message); process.exit(1); });
