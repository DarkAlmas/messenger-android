// Копия, сделанная кодом сайта (web/backup.js) как в app.js createBackup: строка key_backups + объект backup.bin.
// node web_make.js <web-dir> <out.json>  → {row:{salt,kdf,...}, bin:b64}
const B = require(require('path').resolve(process.argv[2], 'backup.js'));
(async () => {
  const uid = '11111111-2222-3333-4444-555555555555', salt = B.newSalt();
  const key = await B.deriveKey('правильный пароль 123', salt, B.DEFAULT_KDF);
  const m = { id: 'aaaaaaaa-0000-0000-0000-000000000001', chat_id: 'cccccccc-0000-0000-0000-000000000001', sender_id: uid, created_at: '2026-10-21T10:00:00+00:00' };
  const msgs = [B.toEntry(m, { t: 'Привет с сайта 👋' }),
    B.toEntry({ ...m, id: 'aaaaaaaa-0000-0000-0000-000000000002' }, { t: '', caption: 'подпись', f: { p: 'chat/cccccccc-0000-0000-0000-000000000001/x_file.bin', n: 'a.jpg', s: 5, k: 'S0VZ', iv: 'SVY=', m: 'image/jpeg', h: 'ab' } })];
  const bin = await B.seal({ v: 1, uid, created_at: new Date().toISOString(), messages: msgs }, key, uid);
  const row = { user_id: uid, version: 1, salt, kdf: B.DEFAULT_KDF, object_path: uid + '/backup.bin', size_bytes: bin.length, updated_at: new Date().toISOString() };
  require('fs').writeFileSync(process.argv[3], JSON.stringify({ row, bin: B.b64e(bin) }));
})();
