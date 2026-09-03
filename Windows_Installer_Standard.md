# Стандарт установщиков Windows для Java-приложений

> Этот документ — полный обязательный стандарт для **новых** Java-приложений, совместимых с AppFleet. Для уже выпущенного приложения, которому нужно добавить только выбор ярлыка рабочего стола без смены остальных механизмов, используйте отдельную [инструкцию миграции](Desktop_Shortcut_Migration.md).

## 1. Назначение

Документ задаёт единые правила сборки, установки и обновления наших Java-приложений под Windows 10/11 x64. Соблюдение стандарта позволяет AppFleet надёжно устанавливать приложение с нуля, определять установленную версию, закрывать приложение и обновлять его без ручного удаления предыдущей версии.

Стандарт обязателен для новых проектов. Для существующих проектов переход выполняется через один миграционный релиз.

## 2. Основные решения

- Публичный дистрибутив: Inno Setup `.exe`.
- MSI не публикуется как основной установщик.
- Java Runtime включается в дистрибутив.
- Установка выполняется для текущего пользователя без постоянного запроса прав администратора.
- Новая версия обновляет предыдущую поверх существующей установки.
- Пользовательские настройки и документы не хранятся внутри каталога программы.
- Все релизы публикуются через GitHub Releases по единой схеме.
- Каждый проект публикует машиночитаемый `appfleet-manifest.json`.
- Каждый новый проект поддерживает необязательный ярлык рабочего стола через Inno Setup task с именем `desktopicon`.

MSI допускается только как отдельный дополнительный корпоративный дистрибутив, если для конкретного проекта действительно потребуется развёртывание административными средствами Windows. Он не должен заменять стандартный EXE.

## 3. Поддерживаемая платформа и инструменты

- Windows 10/11 x64.
- Java 21 LTS x64 либо единая более новая LTS после отдельного решения для всех проектов.
- Gradle Wrapper или Maven Wrapper; wrapper обязателен.
- `jlink`/`jpackage` для формирования app-image со встроенной Runtime.
- Inno Setup 6 для конечного EXE.
- PowerShell-скрипт либо задача сборки для полного формирования релиза одной командой.

Рекомендуемый конвейер:

```text
compile/test → JAR → jlink runtime → jpackage app-image → Inno Setup EXE → SHA-256 → manifest
```

## 4. Идентификаторы и именование

Каждое приложение получает один постоянный UUID `AppId`. Он создаётся один раз и никогда не меняется между версиями, переименованиями и обновлениями.

Пример:

```text
AppId: 3d89ce8a-e1d0-4f04-bff0-c8dc12b8e833
```

Правила:

- отображаемое название может содержать пробелы;
- техническое имя каталога и EXE — латиница, цифры, `-` и `_`;
- основной EXE имеет стабильное имя без номера версии;
- имя установщика содержит версию и архитектуру.

Пример для LocalDrop:

```text
Приложение:       LocalDrop
Основной файл:    LocalDrop.exe
Установщик:       LocalDrop-Setup-1.4.0-x64.exe
Манифест:         appfleet-manifest.json
Контрольная сумма: LocalDrop-Setup-1.4.0-x64.exe.sha256
```

## 5. Версионирование

Использовать SemVer:

```text
MAJOR.MINOR.PATCH
```

Примеры:

```text
1.0.0
1.3.2
2.0.0
```

- `PATCH` — исправления без изменения поведения интерфейсов и данных.
- `MINOR` — новая обратно совместимая функциональность.
- `MAJOR` — несовместимое изменение, включая формат данных, если автоматическая миграция невозможна.
- Версия GitHub tag: `v1.4.0`.
- Версия внутри приложения, Inno Setup, реестра и манифеста: `1.4.0`.
- Версия задаётся в одном месте сборки и автоматически передаётся во все артефакты. Ручное дублирование версии в нескольких файлах запрещено.

## 6. Каталоги

### 6.1. Программные файлы

```text
%LOCALAPPDATA%\Programs\PashaApps\<TechnicalName>
```

Пример:

```text
C:\Users\User\AppData\Local\Programs\PashaApps\LocalDrop
```

Внутри допускается только заменяемое содержимое приложения:

```text
<TechnicalName>.exe
app\
runtime\
icons\
```

### 6.2. Настройки и пользовательские данные

Переносимые настройки:

```text
%APPDATA%\PashaApps\<TechnicalName>
```

Кэш, логи и восстанавливаемые локальные данные:

```text
%LOCALAPPDATA%\PashaApps\<TechnicalName>
```

Документы, проекты, фотографии, видео и иные созданные пользователем файлы сохраняются в выбранное пользователем место либо в стандартные пользовательские каталоги Windows.

Запрещается хранить пользовательские настройки и документы внутри `%LOCALAPPDATA%\Programs\PashaApps\<TechnicalName>`, потому что эта папка заменяется при обновлении.

## 7. Поведение установки

### 7.1. Новая установка

Установщик должен:

- устанавливать приложение в стандартный каталог;
- не требовать отдельно установленной Java;
- зарегистрировать приложение в списке установленных программ Windows;
- создать ярлык в меню «Пуск»;
- предложить ярлык на рабочем столе как необязательную задачу;
- записать метаданные AppFleet в HKCU;
- не запускать приложение неожиданно при тихой установке;
- корректно завершаться с кодом `0` при успехе.

### 7.2. Обновление существующей версии

Для обычного обновления не нужно предварительно полностью удалять приложение. Новый установщик должен:

1. Обнаружить ту же установку по неизменному `AppId`.
2. Предложить закрыть запущенное приложение.
3. Удалить только заменяемые программные файлы предыдущей версии.
4. Установить новые файлы в тот же каталог.
5. Сохранить настройки и пользовательские данные.
6. Обновить версию в Windows Uninstall Registry и в реестре AppFleet.
7. Сохранить пользовательский выбор ярлыков и каталога установки.
8. Не создавать вторую запись приложения в списке установленных программ.

Удаление всей предыдущей установки перед каждым обновлением запрещено как основной сценарий. Оно применяется только в миграционном релизе со старой технологии установки и только по точно известному идентификатору старого продукта.

### 7.3. Очистка старых файлов

Перед копированием новой app-image разрешается удалять только заранее известные заменяемые элементы:

```text
{app}\<TechnicalName>.exe
{app}\app
{app}\runtime
{app}\icons
```

Запрещены широкие удаления `{app}\*`, родительского каталога `PashaApps`, `%LOCALAPPDATA%`, `%APPDATA%` и путей из непроверенных переменных.

Временные файлы новой версии сначала подготавливаются отдельно. При ошибке установщик не должен намеренно удалять уже установленную рабочую версию до того, как новый пакет прошёл базовую проверку целостности.

## 8. Inno Setup

Каждый новый проект использует Inno Setup 6 и следующий эталонный сценарий. Он рассчитан на уже собранный `jpackage` app-image, установку для текущего пользователя и обновление поверх предыдущей версии. Значения, которые передаёт сборка, перечислены в первых пяти проверках `#ifndef`; остальные три `#define` меняются один раз при создании проекта.

`AppId` — строка UUID без фигурных скобок, например `3d89ce8a-e1d0-4f04-bff0-c8dc12b8e833`. В сборку каждой версии передаётся **тот же** `AppId`. Не используйте `AppId={{...}` и не добавляйте директиву `UninstallDisplayVersion`: в Inno Setup отображаемую версию Windows создаёт `AppVersion`.

```ini
#ifndef AppVersion
  #error AppVersion must be supplied by the build
#endif
#ifndef AppId
  #error AppId must be supplied by the build
#endif
#ifndef RepositoryUrl
  #error RepositoryUrl must be supplied by the build
#endif
#ifndef AppImageDir
  #error AppImageDir must be supplied by the build
#endif
#ifndef OutputDir
  #error OutputDir must be supplied by the build
#endif

; Эти три значения постоянны для проекта.
#define AppName "My Product"
#define TechnicalName "MyProduct"
#define MainExecutable "MyProduct.exe"

[Setup]
AppId={#AppId}
AppName={#AppName}
AppVersion={#AppVersion}
UninstallDisplayName={#AppName}
AppPublisher=PashaApps
DefaultDirName={localappdata}\Programs\PashaApps\{#TechnicalName}
DefaultGroupName={#AppName}
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
UsePreviousAppDir=yes
UsePreviousTasks=yes
CloseApplications=yes
RestartApplications=no
Compression=lzma2
SolidCompression=yes
OutputDir={#OutputDir}
OutputBaseFilename={#TechnicalName}-Setup-{#AppVersion}-x64
DisableProgramGroupPage=yes
WizardStyle=modern
SetupIconFile=..\assets\MyProduct.ico
UninstallDisplayIcon={app}\{#MainExecutable}

[Tasks]
Name: "desktopicon"; Description: "Создать ярлык на рабочем столе"; GroupDescription: "Ярлыки:"; Flags: unchecked

[InstallDelete]
; При обновлении удаляются только известные заменяемые элементы app-image.
Type: files; Name: "{app}\{#MainExecutable}"
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"
Type: filesandordirs; Name: "{app}\icons"

[Files]
; App-image должен быть полностью подготовлен до запуска Inno Setup.
Source: "{#AppImageDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#MainExecutable}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#MainExecutable}"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "SchemaVersion"; ValueData: "1"; Flags: uninsdeletekey
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "AppId"; ValueData: "{#AppId}"
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "Name"; ValueData: "{#AppName}"
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "TechnicalName"; ValueData: "{#TechnicalName}"
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "Version"; ValueData: "{#AppVersion}"
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "InstallLocation"; ValueData: "{app}"
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "Executable"; ValueData: "{app}\{#MainExecutable}"
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "ProcessName"; ValueData: "{#MainExecutable}"
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "RepositoryUrl"; ValueData: "{#RepositoryUrl}"
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "InstallerType"; ValueData: "inno"
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "InstalledBy"; ValueData: "installer"
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "Publisher"; ValueData: "PashaApps"

[Run]
; При тихой установке приложение не запускается.
Filename: "{app}\{#MainExecutable}"; Description: "Запустить {#AppName}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; Не удаляйте данные из %APPDATA% и кэш/логи из %LOCALAPPDATA%.
Type: files; Name: "{app}\{#MainExecutable}"
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"
Type: filesandordirs; Name: "{app}\icons"
Type: dirifempty; Name: "{app}"
```

Замените в сценарии только `AppName`, `TechnicalName`, `MainExecutable` и путь `SetupIconFile` на реальные значения проекта. `AppImageDir` обязан указывать на корень app-image, а `OutputDir` — на каталог сборочных артефактов, не на каталог GitHub Release вручную. Внешние приложения не добавляют в этот сценарий код самообновления AppFleet: это специальный механизм только установщика самого AppFleet.

`ArchitecturesAllowed=x64compatible` и `ArchitecturesInstallIn64BitMode=x64compatible` обязательны: они ограничивают запуск x64-совместимой Windows и включают 64-битный режим установки, в том числе на Windows 11 on Arm через эмуляцию x64. `UsePreviousAppDir=yes` сохраняет каталог, а `UsePreviousTasks=yes` — выбранные задачи при обновлении.

### 8.1. Тихий запуск и ярлык рабочего стола

Базовые аргументы, которые новый проект записывает в `installer.silentArgs` manifest:

```text
/VERYSILENT /SUPPRESSMSGBOXES /NORESTART /CLOSEAPPLICATIONS
```

`/VERYSILENT` скрывает мастер установки, а `/NORESTART` запрещает установщику перезагрузить Windows. При невозможности безопасно закрыть процесс установщик завершает операцию с ошибкой; он не должен принудительно уничтожать процесс приложения.

Задача `desktopicon` и условный `{autodesktop}`-ярлык из эталонного сценария обязательны для нового проекта. Обычный интерактивный installer оставляет задачу невыбранной (`Flags: unchecked`). При **первой** тихой установке AppFleet по выбору пользователя дополняет аргументы отдельным `/TASKS=desktopicon`. При обновлении AppFleet этот аргумент не передаёт: Inno Setup сохраняет прежний выбор благодаря `UsePreviousTasks=yes`.

Никогда не добавляйте `/TASKS=desktopicon` в `silentArgs` manifest: тогда ярлык навязывался бы при каждом обновлении. Не создавайте и не удаляйте файл `.lnk` из приложения или AppFleet — это обязанность Inno Setup.

Для уже выпущенного проекта с существующим сценарием используйте [Desktop_Shortcut_Migration.md](Desktop_Shortcut_Migration.md), а не меняйте прежние Release задним числом.

### 8.2. Вызов компилятора из сборки

Сборочная задача запускает `ISCC.exe` без ручного ввода значений в интерфейсе Inno Setup. Эквивалентный вызов выглядит так:

```powershell
& ISCC.exe `
  '/DAppVersion=1.4.0' `
  '/DAppId=3d89ce8a-e1d0-4f04-bff0-c8dc12b8e833' `
  '/DRepositoryUrl=https://github.com/OWNER/MyProduct' `
  '/DAppImageDir=C:\absolute\path\to\app-image\MyProduct' `
  '/DOutputDir=C:\absolute\path\to\build\installer' `
  'installer\MyProduct.iss'
```

В реальной Gradle/Maven-задаче это должны быть отдельные аргументы процесса, а не одна shell-строка. `AppVersion`, `AppId` и `RepositoryUrl` одновременно передаются в jpackage, Inno Setup, registry и генератор manifest из единого конфигурационного источника. После компиляции сборка проверяет, что существует ровно файл `<TechnicalName>-Setup-<version>-x64.exe`.

## 9. Регистрация для AppFleet

Установщик создаёт ключ:

```text
HKCU\Software\PashaApps\<AppId>
```

Обязательные строковые значения:

| Значение | Содержание |
| --- | --- |
| `SchemaVersion` | Версия схемы, первоначально `1` |
| `AppId` | Постоянный UUID приложения |
| `Name` | Отображаемое название |
| `TechnicalName` | Техническое имя |
| `Version` | Установленная версия без `v` |
| `InstallLocation` | Полный каталог установки |
| `Executable` | Полный путь основного EXE |
| `ProcessName` | Имя процесса, например `LocalDrop.exe` |
| `RepositoryUrl` | Каноническая ссылка GitHub |
| `InstallerType` | `inno` |
| `InstalledBy` | `installer` — значение, записываемое стандартным EXE, в том числе при запуске из AppFleet |

Дополнительно допускаются `Publisher`, `UninstallString` и `QuietUninstallString`.

Стандартный `[Registry]` из раздела 8 создаёт все эти значения. Они обновляются только после успешного копирования программных файлов. При обычном удалении приложения ключ удаляется. Пользовательские настройки в `%APPDATA%` по умолчанию не удаляются; их удаление возможно только через отдельный явно сформулированный выбор пользователя.

## 10. Манифест AppFleet

Каждый GitHub Release содержит `appfleet-manifest.json` в UTF-8 без BOM. Для новых проектов используется schema version `1`; повышать её ради ярлыка рабочего стола не нужно. Полный копируемый файл также находится в [examples/appfleet-manifest.json](examples/appfleet-manifest.json). Пример:

```json
{
  "schemaVersion": 1,
  "appId": "3d89ce8a-e1d0-4f04-bff0-c8dc12b8e833",
  "name": "LocalDrop",
  "technicalName": "LocalDrop",
  "version": "1.4.0",
  "repositoryUrl": "https://github.com/OWNER/LocalDrop",
  "platform": "windows",
  "architecture": "x64",
  "installer": {
    "type": "inno",
    "assetName": "LocalDrop-Setup-1.4.0-x64.exe",
    "sha256AssetName": "LocalDrop-Setup-1.4.0-x64.exe.sha256",
    "silentArgs": [
      "/VERYSILENT",
      "/SUPPRESSMSGBOXES",
      "/NORESTART",
      "/CLOSEAPPLICATIONS"
    ],
    "desktopShortcutTask": "desktopicon"
  },
  "detection": {
    "registryKey": "HKCU\\Software\\PashaApps\\3d89ce8a-e1d0-4f04-bff0-c8dc12b8e833",
    "versionValue": "Version",
    "executableValue": "Executable"
  },
  "processNames": ["LocalDrop.exe"],
  "minimumAppFleetVersion": "1.0.0"
}
```

Требования:

- неизвестные необязательные поля игнорируются для прямой совместимости;
- отсутствие обязательного поля делает манифест недействительным;
- `assetName` должен точно соответствовать asset текущего релиза;
- `version` должен соответствовать версии релиза;
- `silentArgs` передаются как массив аргументов, а не как shell-строка;
- новые проекты обязательно задают `installer.desktopShortcutTask: "desktopicon"`; отсутствие поля допускается только для старого Release, ещё не прошедшего миграцию;
- `desktopShortcutTask` — только имя разрешённой Inno Setup task, без пробелов, слешей и аргументов; стандартное и единственно допустимое имя для новых проектов — `desktopicon`;
- `/TASKS=desktopicon` не входит в `silentArgs`: AppFleet добавляет его самостоятельно и только при первой установке с включённой настройкой ярлыка;
- поле остаётся обратно совместимым со schema 1: старые версии AppFleet могут проигнорировать его, а AppFleet 1.0.9 и новее используют его безопасно;
- манифест не может задавать произвольную программу для запуска;
- AppFleet поддерживает только заранее разрешённые значения `installer.type`.

## 11. GitHub Release

Каждый stable-релиз содержит ровно один основной Windows x64 EXE-установщик и обязательные служебные файлы:

```text
<TechnicalName>-Setup-<version>-x64.exe
<TechnicalName>-Setup-<version>-x64.exe.sha256
appfleet-manifest.json
```

Дополнительные файлы допускаются, но не должны иметь имена, которые можно спутать с основным Windows x64 установщиком. Исходный ZIP, отладочный ZIP, второй EXE и установщик другой архитектуры не заменяют обязательный EXE.

Формат файла SHA-256:

```text
<64 hexadecimal characters>  <TechnicalName>-Setup-<version>-x64.exe
```

Tag имеет вид `v<version>`, а `version` в manifest — тот же номер без `v`. Описание GitHub Release кратко перечисляет пользовательские изменения и указывает, если EXE пока не подписан Authenticode. Draft и prerelease не считаются обычным стабильным обновлением.

## 12. Миграция существующих EXE и MSI

Для каждого существующего приложения перед миграцией отдельно определить:

- тип и версию старого установщика;
- старый каталог установки;
- старый `AppId`, MSI `UpgradeCode`/`ProductCode` или Uninstall Registry key;
- точную команду тихого удаления;
- расположение настроек и данных;
- имя запущенного процесса;
- возможные ярлыки и автозапуск.

Первая стандартизированная версия должна:

1. Найти только конкретную известную старую установку.
2. Сохранить или перенести настройки в стандартный каталог.
3. Закрыть старое приложение после согласия пользователя.
4. Запустить официальный механизм удаления старого MSI/EXE либо выполнить безопасное обновление на месте, если формат совместим.
5. Установить новую версию.
6. Проверить запуск и наличие новой записи реестра.
7. Не удалять пользовательские документы.

Для MSI использовать `msiexec` только с заранее известным ProductCode либо UninstallString. Искать и удалять MSI по похожему названию запрещено.

Для старого EXE использовать только его зарегистрированный UninstallString и документированные silent-параметры. Если тихое удаление не поддерживается, миграция выполняется интерактивно.

Миграционный код удаляется из следующих релизов после принятого периода совместимости либо остаётся как безопасная идемпотентная проверка. Он не должен срабатывать на уже стандартизированной установке.

## 13. Удаление приложения

Стандартный деинсталлятор удаляет:

- программные файлы;
- ярлыки;
- запись Windows Uninstall;
- ключ регистрации AppFleet.

Он не удаляет автоматически:

- пользовательские документы;
- настройки в `%APPDATA%`;
- кэш и логи, если пользователь не выбрал их удаление явно.

Если предлагается полная очистка, выбор должен быть отдельным и по умолчанию выключенным.

## 14. Автоматизация сборки

В каждом проекте должна быть одна документированная команда формирования release-кандидата, например:

```powershell
.\gradlew.bat clean test buildWindowsInstaller -Pversion=1.4.0
```

Название Gradle/Maven-задачи может отличаться, но последовательность и результат обязательны. Команда должна:

1. Проверить формат версии.
2. Запустить тесты.
3. Собрать JAR, минимальную Runtime через `jlink` и app-image через `jpackage`.
4. Скомпилировать Inno Setup EXE из эталонного сценария раздела 8.
5. Подписать EXE Authenticode, если сертификат уже доступен.
6. Вычислить SHA-256 только для окончательного EXE.
7. Сформировать `appfleet-manifest.json` из тех же параметров версии, имени и `AppId`.
8. Проверить соответствие версий и имён файлов, SHA-256 и обязательных полей manifest.
9. Поместить готовые файлы в один каталог `dist/release/<version>`.

Формирование установщика не должно зависеть от ручного переименования файлов или ручной правки манифеста.

GitHub Actions workflow должен создавать те же артефакты и запускать ту же проверку метаданных. Публикация GitHub Release может оставаться отдельным подтверждаемым шагом, но не должна пересобирать или переименовывать файл вручную.

## 15. Цифровая подпись

- При наличии сертификата EXE подписывается Authenticode после сборки и до вычисления SHA-256.
- SHA-256 вычисляется только для окончательно подписанного файла.
- Отметка времени при подписи обязательна.
- До появления сертификата допускаются неподписанные наши установщики: AppFleet предупреждает об отсутствии подписи, но при совпадении SHA-256 продолжает установку.
- Недействительная, повреждённая или не прошедшая проверку Authenticode подпись — ошибка: такой EXE AppFleet не запускает.
- Нельзя выдавать самоподписанный тестовый сертификат за рабочую подпись или менять EXE после публикации его checksum.

## 16. Проверки перед выпуском

Для каждого релиза проверить:

1. Установку на чистый профиль Windows без Java.
2. Запуск приложения из меню «Пуск» и корректность иконки приложения.
3. Первую интерактивную установку: задача «Создать ярлык на рабочем столе» показана и не выбрана по умолчанию.
4. Первую тихую установку с базовыми `silentArgs`: ярлык не создаётся и приложение не запускается.
5. Первую тихую установку с дополнительным `/TASKS=desktopicon`: ярлык создаётся и запускает главный EXE.
6. Обновление минимум с предыдущей версии с ранее созданным ярлыком: ярлык остаётся.
7. Обновление без ранее созданного ярлыка: новый ярлык не появляется.
8. Сохранение настроек и пользовательских данных после обновления.
9. Отсутствие второй записи в списке установленных программ и сохранение выбранного каталога установки.
10. Наличие точных значений HKCU для AppFleet: версия, путь EXE, имя процесса, URL репозитория и `InstallerType=inno`.
11. Корректный код завершения установщика и понятная ошибка, если запущенное приложение не удаётся закрыть.
12. Соответствие версии в приложении, реестре, установщике, имени файла, manifest и GitHub tag.
13. Проверку SHA-256 окончательного EXE; при наличии подписи — её валидность.
14. Удаление приложения: ярлык и ключ AppFleet удаляются, пользовательские документы, настройки, кэш и логи без явного выбора не удаляются.
15. Для первого стандартного релиза существующего продукта — миграцию с каждого реально использовавшегося старого установщика.

## 17. Критерии соответствия стандарту

Приложение считается совместимым с AppFleet, если:

- имеет постоянный `AppId`;
- устанавливается стандартным Inno Setup EXE;
- содержит встроенную Java Runtime;
- хранит данные отдельно от программных файлов;
- корректно обновляется поверх предыдущей версии;
- сохраняет каталог установки и выбор задачи `desktopicon` при обновлении;
- записывает обязательные значения реестра;
- публикует установщик, SHA-256 и валидный `appfleet-manifest.json` schema 1 с `desktopShortcutTask: "desktopicon"`;
- поддерживает тихий запуск с базовыми `silentArgs` и отдельной task только для первой установки;
- не требует ручного удаления предыдущей версии;
- прошло проверки раздела 16.
