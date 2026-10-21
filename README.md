# Мессенджер — нативное Android-приложение

Пакет `com.darkcompany.messenger.nativeapp`. Сквозное шифрование (libsignal), голосовые и кружки, офлайн-кэш, галочки «доставлено/прочитано», обновления из GitHub Releases.

Сборка: `gradle assembleDebug` (JDK 17, Android SDK 37). Релизы: тег `v<versionCode>` + APK во вложении.

## Резервная копия (v16, как на сайте)
Настройки → «Резервная копия»: «Автокопия» (раз в сутки и после выхода из чата), «Сохранить сейчас». Формат и хранение — docs/backup_format.md:
Storage `backups/{uid}/backup.bin` + строка `key_backups` (salt, kdf); шифр = `web/backup.js` (Argon2id → AES-256-GCM, AAD `msgr-backup:v1:<uid>`).
Пароль не хранится; для автокопии хранится только ключ Argon2id, обёрнутый ключом Android Keystore (StrongBox/TEE), стирается при выходе и выключении.
Совместимость с сайтом: `V16BackupTest` + `tools/backup-interop/*.js`.
