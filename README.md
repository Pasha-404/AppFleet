# AppFleet

AppFleet — Windows-приложение на Java 21 и JavaFX для установки и обновления приложений из stable GitHub Releases. Оно принимает только публичные `github.com`-ссылки, использует GitHub REST API, требует отдельного подтверждения каждой установки и поддерживает `.exe`, `.msi` и безопасно заменяемые portable `.zip`.

## Возможности

- Добавление ссылок на корень репозитория, `/releases` или `/releases/tag/<tag>` с нормализацией до `OWNER/REPOSITORY`.
- Проверка stable-релизов с `ETag`/`If-None-Match`, обработкой rate limit и сохранением последних корректных данных.
- Проверенный `appfleet-manifest.json` имеет приоритет; иначе файл выбирается консервативно, без выбора первого равнозначного asset.
- SHA-256 обязателен для стандартных Inno Setup релизов; недействительная Authenticode-подпись блокирует запуск.
- Подтверждённая установка Inno EXE, интерактивный сторонний EXE, MSI через `msiexec` и ZIP с защитой от Zip Slip/откатом.
- Проверка самообновления AppFleet выполняется до проверки добавленных приложений.
- Сохраняемый журнал, настройки и список репозиториев. Данные не находятся в каталоге программы.

## Требования для разработки

- Windows 10/11 x64.
- JDK 21 x64.
- Inno Setup 6 для `buildWindowsInstaller`.

Gradle Wrapper включён в репозиторий. JavaFX JMODs для `jlink` автоматически скачиваются с закреплённого официального URL Gluon и сверяются с SHA-256; они не коммитятся в Git.

## Запуск и тестирование

```powershell
.\gradlew.bat test
.\gradlew.bat run
```

Live-проверка использует реальный публичный релиз [SortIt v1.5.0](https://github.com/Pasha-404/sortit/releases/tag/v1.5.0), но запускается отдельно, чтобы обычные тесты не зависели от сети:

```powershell
.\gradlew.bat liveIntegrationTest
```

## Сборка Windows-релиза

```powershell
.\scripts\build-release.ps1 -Version 1.0.0 -RepositoryUrl "https://github.com/OWNER/AppFleet"
```

Либо непосредственно через Gradle (PowerShell property передаются отдельными строковыми аргументами):

```powershell
.\gradlew.bat clean test buildWindowsInstaller '-Pversion=1.0.0' '-PappfleetRepositoryUrl=https://github.com/OWNER/AppFleet'
```

Результат находится в `dist/release/<version>`:

- `AppFleet-Setup-<version>-x64.exe`;
- `AppFleet-Setup-<version>-x64.exe.sha256`;
- `appfleet-manifest.json`.

Версия определена одним Gradle property и переносится в JAR, app-image, Inno Setup, Registry и manifest. Значение `appfleetRepositoryUrl` обязательно нужно передать с фактическим опубликованным репозиторием AppFleet: по нему приложение ищет собственные обновления.

При наличии сертификата подпись добавляется до вычисления SHA-256:

```powershell
.\gradlew.bat buildWindowsInstaller '-Pversion=1.0.0' '-PappfleetRepositoryUrl=https://github.com/OWNER/AppFleet' '-PsignCertificateThumbprint=<thumbprint>'
```

Используется `signtool` из PATH и timestamp URL `http://timestamp.digicert.com`, который можно заменить `-PtimestampUrl=...`.

## Данные и установка

| Назначение | Путь |
| --- | --- |
| Программа | `%LOCALAPPDATA%\Programs\PashaApps\AppFleet` |
| Настройки и список | `%APPDATA%\PashaApps\AppFleet` |
| Кэш и логи | `%LOCALAPPDATA%\PashaApps\AppFleet` |
| Временные операции | `%TEMP%\AppFleet` |

Inno Setup устанавливает приложение для текущего пользователя, добавляет запись «Установленные приложения», ярлык меню «Пуск», опциональный ярлык рабочего стола и точную запись `HKCU\Software\PashaApps\<AppId>`. При обновлении удаляются только `AppFleet.exe`, `app`, `runtime` и `icons`; пользовательские настройки и журнал не удаляются. Удаление также не затрагивает `%APPDATA%` и `%LOCALAPPDATA%` с данными.

## Структура и выпуск

Архитектура и план находятся в [PROJECT_STRUCTURE.md](PROJECT_STRUCTURE.md), ход работы — в [PROGRESS.md](PROGRESS.md). Шаблон установщика — [installer/AppFleet.iss](installer/AppFleet.iss), пример release-manifest — [examples/appfleet-manifest.json](examples/appfleet-manifest.json), workflow — [.github/workflows/build-release.yml](.github/workflows/build-release.yml).

Inno Setup 6 не имеет директивы `UninstallDisplayVersion`, хотя она упомянута в исходном стандарте. Вместо неё используется поддерживаемая `AppVersion`; компилятор Inno Setup создаёт корректную DisplayVersion в Uninstall Registry.
