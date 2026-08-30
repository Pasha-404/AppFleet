# AppFleet

AppFleet — Windows-приложение на Java 21 и JavaFX для установки и обновления приложений из stable GitHub Releases. Оно принимает только публичные `github.com`-ссылки, использует GitHub REST API, требует подтверждения каждой установки и поддерживает `.exe`, `.msi` и безопасно заменяемые portable `.zip`.

## Установка

Скачайте `AppFleet-Setup-<version>-x64.exe` на странице [Releases](https://github.com/Pasha-404/AppFleet/releases), при необходимости сверьте его с опубликованным файлом `.sha256` и запустите установщик. Java отдельно не нужна: Runtime встроена в приложение.

Первый выпуск пока не подписан Authenticode, поэтому Windows может показать предупреждение о неподписанном EXE. AppFleet проверяет SHA-256 установщика перед самообновлением.

## Возможности

- Добавление ссылок на корень репозитория, `/releases` или `/releases/tag/<tag>` с нормализацией до `OWNER/REPOSITORY`.
- Проверка stable-релизов с `ETag`/`If-None-Match`, обработкой rate limit и сохранением последних корректных данных.
- Приоритет валидного `appfleet-manifest.json`; иначе — консервативный выбор asset без выбора первого равнозначного файла.
- Проверка SHA-256, проверка Authenticode и безопасная установка Inno EXE, сторонних EXE, MSI и ZIP.
- Самообновление AppFleet до проверки добавленных приложений.
- Сохраняемый журнал, настройки и список репозиториев. Данные не находятся в каталоге программы.

## Требования для разработки

- Windows 10/11 x64.
- JDK 21 x64.
- Inno Setup 6 для `buildWindowsInstaller`.

Gradle Wrapper включён в репозиторий. JavaFX JMODs для `jlink` автоматически скачиваются с закреплённого официального URL Gluon и сверяются с SHA-256; они не коммитятся в Git.

## Запуск и проверка

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
.\scripts\build-release.ps1 -Version 1.0.0 -RepositoryUrl "https://github.com/Pasha-404/AppFleet"
```

Либо непосредственно через Gradle:

```powershell
.\gradlew.bat clean test buildWindowsInstaller '-Pversion=1.0.0' '-PappfleetRepositoryUrl=https://github.com/Pasha-404/AppFleet'
```

Результат находится в `dist/release/<version>`:

- `AppFleet-Setup-<version>-x64.exe`;
- `AppFleet-Setup-<version>-x64.exe.sha256`;
- `appfleet-manifest.json`.

Версия определяется одним Gradle property и переносится в JAR, app-image, Inno Setup, Registry и manifest. При наличии сертификата подпись добавляется до вычисления SHA-256:

```powershell
.\gradlew.bat buildWindowsInstaller '-Pversion=1.0.0' '-PappfleetRepositoryUrl=https://github.com/Pasha-404/AppFleet' '-PsignCertificateThumbprint=<thumbprint>'
```

Используется `signtool` из PATH и timestamp URL `http://timestamp.digicert.com`, который можно заменить `-PtimestampUrl=...`.

## Публикация приложения, совместимого с AppFleet

AppFleet проверяет только stable GitHub Releases публичного репозитория. Для каждого выпуска публикуйте tag формата `v<SemVer>` и три asset в одном Release:

| Asset | Назначение |
| --- | --- |
| `<TechnicalName>-Setup-<version>-x64.exe` | Стандартный Inno Setup installer для Windows x64. |
| `<TechnicalName>-Setup-<version>-x64.exe.sha256` | SHA-256 окончательного EXE: 64 символа в нижнем регистре, два пробела, имя EXE. |
| `appfleet-manifest.json` | Валидный manifest схемы 1. |

Не публикуйте стабильный выпуск как Draft или Prerelease. Подписывайте EXE Authenticode до вычисления SHA-256, если сертификат доступен. Не заменяйте уже опубликованные assets другой версией файлов.

Используйте [шаблон manifest](examples/appfleet-manifest.json) и замените примерные идентификаторы своими данными:

```json
{
  "schemaVersion": 1,
  "appId": "11111111-2222-4333-8444-555555555555",
  "name": "My Product",
  "technicalName": "MyProduct",
  "version": "1.4.0",
  "repositoryUrl": "https://github.com/OWNER/MyProduct",
  "platform": "windows",
  "architecture": "x64",
  "installer": {
    "type": "inno",
    "assetName": "MyProduct-Setup-1.4.0-x64.exe",
    "sha256AssetName": "MyProduct-Setup-1.4.0-x64.exe.sha256",
    "silentArgs": ["/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/CLOSEAPPLICATIONS"]
  },
  "detection": {
    "registryKey": "HKCU\\Software\\PashaApps\\11111111-2222-4333-8444-555555555555",
    "versionValue": "Version",
    "executableValue": "Executable"
  },
  "processNames": ["MyProduct.exe"],
  "minimumAppFleetVersion": "1.0.0"
}
```

`appId` — постоянный UUID приложения: не меняйте его между релизами. `technicalName`, версия manifest, имена installer и SHA-256 asset должны точно совпадать с текущим Release. Для Inno Setup обязательны `installer.type: "inno"`, SHA-256 asset и разрешённые silent-аргументы из примера. Установщик должен записывать `HKCU\\Software\\PashaApps\\<AppId>` со значениями `Version` и `Executable`. AppFleet отвергает manifest с несовпадающими данными и вместо него потребует безопасный ручной выбор файла.

## Иконка

Исходник иконки — [assets/AppFleet.svg](assets/AppFleet.svg), а многоразмерный Windows ICO — [assets/AppFleet.ico](assets/AppFleet.ico). После изменения дизайна выполните:

```powershell
.\scripts\generate-icon.ps1
```

Скрипт создаёт размеры 16, 24, 32, 48, 64, 128 и 256 px. Сборка использует ICO для app-image и Inno Setup.

## Данные и установка

| Назначение | Путь |
| --- | --- |
| Программа | `%LOCALAPPDATA%\Programs\PashaApps\AppFleet` |
| Настройки и список | `%APPDATA%\PashaApps\AppFleet` |
| Кэш и логи | `%LOCALAPPDATA%\PashaApps\AppFleet` |
| Временные операции | `%TEMP%\AppFleet` |

Inno Setup устанавливает приложение для текущего пользователя, добавляет запись «Установленные приложения», ярлык меню «Пуск», опциональный ярлык рабочего стола и точную запись `HKCU\Software\PashaApps\<AppId>`. При обновлении удаляются только `AppFleet.exe`, `app`, `runtime` и `icons`; пользовательские настройки и журнал не удаляются. Удаление также не затрагивает `%APPDATA%` и `%LOCALAPPDATA%` с данными.

## Автоматизация выпуска

GitHub Actions собирает те же assets, что и локальная команда. Push тега `v<SemVer>` запускает сборку Windows, публикует артефакты и создаёт GitHub Release с установщиком, SHA-256 и manifest. Ручной запуск workflow создаёт только build artifacts.

Архитектура и план находятся в [PROJECT_STRUCTURE.md](PROJECT_STRUCTURE.md), ход работы — в [PROGRESS.md](PROGRESS.md), история выпуска — в [CHANGELOG.md](CHANGELOG.md). Шаблон установщика — [installer/AppFleet.iss](installer/AppFleet.iss), workflow — [.github/workflows/build-release.yml](.github/workflows/build-release.yml).

Inno Setup 6 не имеет директивы `UninstallDisplayVersion`, хотя она упомянута в исходном стандарте. Вместо неё используется поддерживаемая `AppVersion`; компилятор Inno Setup создаёт корректную DisplayVersion в Uninstall Registry.
