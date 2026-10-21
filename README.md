# Мессенджер — нативное Android-приложение

Пакет `com.darkcompany.messenger.nativeapp`. Сквозное шифрование (libsignal), голосовые и кружки, офлайн-кэш, галочки «доставлено/прочитано», обновления из GitHub Releases.

Сборка: `gradle assembleDebug` (JDK 17, Android SDK 37). Релизы: тег `v<versionCode>` + APK во вложении.

## Резервная копия (v15)
Настройки → «Резервная копия»: «Создать копию» (пароль ≥ 12 символов дважды → системное «Сохранить в…») и «Восстановить из файла».
Файл `.msgrbak` — JSON `{"format":"msgr-backup","v":1,"uid","salt","kdf":{"alg":"argon2id","m":65536,"t":3,"p":1},"created_at","data"}`,
где `data` = base64 от `backup.bin` ровно как в `web/backup.js` (nonce‖AES-256-GCM, AAD `msgr-backup:v1:<uid>`, ключ Argon2id).
На сервер не загружается; ключей устройства в копии нет. Совместимость с сайтом: `V15BackupTest` + `tools/backup-interop/*.js`.
