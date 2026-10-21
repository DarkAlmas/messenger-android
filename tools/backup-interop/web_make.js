// Делает файл копии из web/backup.js (сайт): node web_make.js <web-dir> <out.msgrbak>
const B = require(require('path').resolve(process.argv[2], 'backup.js'));
(async () => {
  const uid = '11111111-2222-3333-4444-555555555555', salt = B.newSalt();
  const key = await B.deriveKey('правильный пароль 123', salt, B.DEFAULT_KDF);
  const m = { id: 'm-1', chat_id: 'c-1', sender_id: uid, created_at: '2026-10-21T10:00:00+00:00' };
  const msgs = [B.toEntry(m, { t: 'Привет с сайта 👋' }), B.toEntry({ ...m, id: 'm-2' }, { t: '', caption: 'подпись', f: { p: 'chat/c-1/x_file.bin', n: 'a.jpg', s: 5, k: 'S0VZ', iv: 'SVY=', m: 'image/jpeg', h: 'ab' } })];
  const bin = await B.seal({ v: 1, uid, created_at: new Date().toISOString(), messages: msgs }, key, uid);
  require('fs').writeFileSync(process.argv[3], JSON.stringify({ format: 'msgr-backup', v: 1, uid, salt, kdf: B.DEFAULT_KDF, created_at: new Date().toISOString(), data: B.b64e(bin) }));
})();
