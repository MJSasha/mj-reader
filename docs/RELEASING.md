# Автоматические релизы

GitHub Actions проверяет push в `main` и pull request. Тег `vX.Y.Z` запускает отдельный
Release workflow: подтверждение успешного CI для того же коммита → сборка подписанного release APK → проверка подписи
и версии → публикация GitHub Release с APK, SHA256SUMS и заметками из CHANGELOG.

## Подпись и обновления

Application ID остаётся `com.mjreader`. Для ранних 0.x-сборок сохраняется сертификат,
которым подписаны уже установленные APK. Его публичный SHA-256 зафиксирован в
`docs/release-certificate.sha256`; приватный ключ в Git не хранится.

Сертификат унаследован от первоначальных development-сборок. CI собирает вариант
`release` с `debuggable=false`, но это не настройка публикации в Google Play. Версии
0.x помечаются prerelease. Менять ключ ради названия сертификата нельзя: это нарушит
обновление существующих установок. Миграцию подписи нужно проектировать отдельно.

## Однократная настройка GitHub Secrets

В Settings → Secrets and variables → Actions должны быть четыре repository secrets:

| Secret | Значение |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 существующего keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Пароль хранилища |
| `ANDROID_KEY_ALIAS` | Alias ключа |
| `ANDROID_KEY_PASSWORD` | Пароль ключа |

Ключ не должен попадать в issue, чат, artifact или лог. GitHub хранит secrets зашифрованными.
Workflow восстанавливает keystore только во временном каталоге runner с закрытыми правами
и удаляет его после шага. Pull request проверки не получают secrets подписи.

Обычная сборка debug не требует этих секретов. Локальный `assembleRelease` без переменных
может собрать unsigned APK; скрипт упаковки и workflow никогда его не публикуют.

## Выпустить версию

1. Обновите `VERSION_NAME` и увеличьте `VERSION_CODE` в `version.properties`.
2. Добавьте раздел `## [X.Y.Z] — YYYY-MM-DD` в `CHANGELOG.md`.
3. Проверьте изменения и отправьте их в `main`; дождитесь зелёного Checks.
4. Создайте и отправьте тег:

```sh
python3 scripts/version.py --tag vX.Y.Z
python3 scripts/release-notes.py X.Y.Z
git tag -a vX.Y.Z -m 'MJReader X.Y.Z'
git push origin vX.Y.Z
```

Тег должен совпадать с `version.properties`, а commit — входить в историю `main`.
Release через GitHub API проверяет успешный push-прогон `.github/workflows/ci.yml`
в `main` для **точного SHA тега** из этого же репозитория. Старый зелёный коммит,
PR-прогон, незавершённый или упавший CI не подходят. Повторять build/lint/JVM/Android
проверки для тега не нужно. Если тег отправлен раньше завершения CI, дождитесь
успеха и перезапустите Release; новую версию для такого повтора делать не требуется.
Обязательные Android-наборы (`smoke,catalog` и `motion,updates`) идут в двух
параллельных изолированных эмуляторах. Публикация разрешена только после их успеха.
Полные `visual,adaptive` вынесены в ручной прогон Checks с `full_ui=true` и не
задерживают каждый релиз. Проверки сборки, lint/JVM, подписи и версии APK обязательны. Assets: `MJReader-X.Y.Z.apk`, `SHA256SUMS`.

## Локальная проверка подписанного APK

Передайте `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`
и `ANDROID_KEY_PASSWORD` через окружение из вашего защищённого хранилища.

```sh
./gradlew assembleRelease
python3 scripts/package-release.py
```

Скрипт сверяет сертификат, versionName/versionCode, отсутствие `debuggable` и тестовых
секретов/книг. Готовые файлы находятся в `dist/release/`, который исключён из Git.

## Если workflow упал

- Откройте failed job и его artifacts: lint HTML, Android logs/JUnit, screenshots.
- Нет signing secret: настройте четыре значения и перезапустите failed workflow.
- Не совпала подпись: проверьте исходный keystore. Не меняйте ожидаемый fingerprint,
  чтобы просто заставить проверку пройти.
- Ошибка версии/исходников: исправьте в новом commit и выпустите новый version/tag.
- Уже опубликованный релиз не перезаписывается. Новый APK получает новую версию.
- Если загрузка assets оставила draft release, сначала проверьте его состояние;
  не удаляйте опубликованные релизы или теги автоматически.

Документация: [GitHub Secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets),
[GitHub CLI Releases](https://cli.github.com/manual/gh_release_create),
[Android app signing](https://developer.android.com/studio/publish/app-signing).

## OTA updates

The app discovers published entries through the releases list (not `/releases/latest`, which excludes prereleases). Keep tags `vX.Y.Z`, asset names `MJReader-X.Y.Z.apk`, application ID and signing identity stable, and increase the version code. The updater requires GitHub's asset `digest` (`sha256:`); an incomplete release or asset without it is skipped. Publication remains gated by the full CI checks. Existing installations predating OTA support need one manual APK update; subsequent updates can be downloaded from Settings → Updates and confirmed in Android's installer.
