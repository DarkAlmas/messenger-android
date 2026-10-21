// Открывает файл копии, созданный Android, через web/backup.js: node web_open.js <web-dir> <in.msgrbak> <пароль> <uid>
const B = require(require('path').resolve(process.argv[2], 'backup.js'));
(async () => {
  const f = JSON.parse(require('fs').readFileSync(process.argv[3], 'utf8'));
  const key = await B.deriveKey(process.argv[4], f.salt, f.kdf);
  const o = await B.open(B.b64d(f.data), key, process.argv[5]);
  console.log(JSON.stringify(o.messages.map(B.toCache)));
})().catch(e => { console.error('ERR', e.message); process.exit(1); });
