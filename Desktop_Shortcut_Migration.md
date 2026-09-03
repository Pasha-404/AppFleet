# Миграция готового Windows-приложения: ярлык рабочего стола для AppFleet

Этот документ предназначен для уже выпущенных Java-приложений с Windows-установщиком Inno Setup, которые должны поддержать настройку AppFleet «Создавать ярлык на рабочем столе при первой установке».

Цель миграции — дать AppFleet возможность создать ярлык только при первой установке и только по выбору пользователя. При обновлении приложение обязано сохранить прежний выбор: не создавать новый ярлык и не удалять существующий. Ярлык должен создавать и удалять сам Inno Setup, а не AppFleet.

## Результат, который обязателен после миграции

После выпуска новой версии должны одновременно выполняться все условия:

1. Inno Setup содержит задачу с точным именем `desktopicon`.
2. Ярлык рабочего стола создаётся только при выбранной задаче `desktopicon`.
3. В `appfleet-manifest.json` текущего Release задано `installer.desktopShortcutTask: "desktopicon"`.
4. При обновлении installer сохраняет ранее выбранные задачи.
5. В `silentArgs` manifest нет аргумента `/TASKS=desktopicon`.

Если отсутствует хотя бы одно условие, приложение не заявляет поддержку ярлыка через AppFleet.

## Что не нужно делать

- Не менять уже опубликованные GitHub Release и их assets.
- Не менять постоянный `AppId` Inno Setup и `appId` manifest.
- Не создавать файл `.lnk` из кода приложения или из AppFleet.
- Не добавлять `/TASKS=desktopicon` в `silentArgs`.
- Не использовать другое имя задачи: `desktop`, `desktop-shortcut`, `createDesktopIcon` и переводы не подходят.
- Не оставлять одновременно безусловный ярлык рабочего стола и ярлык с условием `Tasks: desktopicon`.

Миграция входит в следующий обычный релиз приложения. Отдельный миграционный installer не требуется.

## 1. Измените Inno Setup-скрипт

### 1.1. Сохраните выбор задач при обновлении

В секции `[Setup]` добавьте явную директиву:

```ini
UsePreviousTasks=yes
```

Не меняйте существующий `AppId`. Эта директива нужна, чтобы при обновлении installer восстановил ранее выбранную задачу `desktopicon`.

Минимальный фрагмент секции выглядит так:

```ini
[Setup]
; Существующий постоянный AppId. Не меняйте его.
AppId={#AppId}
AppName={#AppName}
AppVersion={#AppVersion}
DefaultDirName={localappdata}\Programs\PashaApps\{#TechnicalName}
UsePreviousAppDir=yes
UsePreviousTasks=yes
PrivilegesRequired=lowest
CloseApplications=yes
RestartApplications=no
```

### 1.2. Добавьте задачу с точным именем

Добавьте в скрипт секцию `[Tasks]` либо строку в уже существующую секцию:

```ini
[Tasks]
Name: "desktopicon"; Description: "Создать ярлык на рабочем столе"; GroupDescription: "Ярлыки:"; Flags: unchecked
```

Требования к строке:

- `Name` строго равен `desktopicon`;
- `Flags: unchecked` обязателен: обычный запуск installer не создаёт ярлык без выбора пользователя;
- `Description` и `GroupDescription` можно локализовать, но имя задачи менять нельзя.

### 1.3. Создайте условный ярлык

В секции `[Icons]` добавьте ровно одну строку для рабочего стола:

```ini
[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#MainExecutable}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#MainExecutable}"; Tasks: desktopicon
```

Замените `{#MainExecutable}` на фактическое имя главного EXE, если в проекте используется другое определение. Ярлык меню «Пуск» сохраняется без условия. Если в скрипте уже есть безусловная строка с `{autodesktop}`, замените её на строку с `Tasks: desktopicon`; две строки оставлять нельзя.

## 2. Измените генератор manifest

Используется прежняя schema version:

```json
"schemaVersion": 1
```

Версию схемы не повышайте. `desktopShortcutTask` — необязательное поле schema 1, поэтому старые версии AppFleet его безопасно проигнорируют, а AppFleet 1.0.9 и новее используют его при первой установке.

В объект `installer` сгенерированного `appfleet-manifest.json` добавьте ровно одно поле:

```json
"desktopShortcutTask": "desktopicon"
```

Полный шаблон manifest приведён ниже. Он также находится в [examples/appfleet-manifest.json](examples/appfleet-manifest.json).

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
    "silentArgs": [
      "/VERYSILENT",
      "/SUPPRESSMSGBOXES",
      "/NORESTART",
      "/CLOSEAPPLICATIONS"
    ],
    "desktopShortcutTask": "desktopicon"
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

Замените все примерные значения на реальные. `assetName`, `sha256AssetName` и `version` должны в точности соответствовать assets и tag нового Release. Поле `desktopShortcutTask` должно оставаться строкой `desktopicon`.

## 3. Как AppFleet использует installer

AppFleet не добавляет `/TASKS=desktopicon` в manifest и не передаёт его при обновлении.

| Сценарий | Аргументы installer от AppFleet | Ожидаемый результат |
| --- | --- | --- |
| Первая установка, настройка ярлыка выключена | Только `silentArgs` из manifest | Ярлык рабочего стола не создаётся. |
| Первая установка, настройка ярлыка включена | `silentArgs` и отдельный аргумент `/TASKS=desktopicon` | Inno Setup создаёт ярлык. |
| Обновление с ранее созданным ярлыком | Только `silentArgs` из manifest | Inno Setup сохраняет ярлык. |
| Обновление без ранее созданного ярлыка | Только `silentArgs` из manifest | Новый ярлык не появляется. |
| Удаление приложения | Стандартный деинсталлятор Inno Setup | Inno Setup удаляет созданный им ярлык. |

Поэтому `/TASKS=desktopicon` нельзя записывать в `silentArgs`: иначе ярлык будет навязываться при каждом обновлении.

## 4. Обязательная проверка перед Release

Выполните все пять сценариев с installer новой версии.

1. Чистая интерактивная установка: task не отмечен по умолчанию, ярлык не появляется.
2. Чистая тихая установка: запустите installer с обычными параметрами и отдельным `/TASKS=desktopicon`; ярлык появляется и запускает главный EXE.
3. Обновление установки с ярлыком: запустите новую версию installer без `/TASKS`; ярлык остаётся и ведёт к обновлённому EXE.
4. Обновление установки без ярлыка: запустите новую версию installer без `/TASKS`; ярлык не появляется.
5. Удаление: деинсталлятор удаляет ярлык, но не удаляет пользовательские данные в `%APPDATA%` и `%LOCALAPPDATA%`.

После этого соберите Release обычной командой проекта и проверьте его assets:

```text
<TechnicalName>-Setup-<version>-x64.exe
<TechnicalName>-Setup-<version>-x64.exe.sha256
appfleet-manifest.json
```

Проверьте, что опубликованный `appfleet-manifest.json` содержит `desktopShortcutTask: "desktopicon"`, а SHA-256 рассчитан после финальной сборки и подписи EXE.

## Короткая задача для владельца проекта

> В следующем Windows-релизе добавьте поддержку ярлыка AppFleet. В Inno Setup задайте `UsePreviousTasks=yes`, task `desktopicon` с `Flags: unchecked` и условный `{autodesktop}`-ярлык с `Tasks: desktopicon`. В автоматически генерируемый `appfleet-manifest.json` schema 1 добавьте `installer.desktopShortcutTask: "desktopicon"`. Не меняйте `AppId`, не добавляйте `/TASKS=desktopicon` в `silentArgs` и проверьте чистую установку, оба варианта обновления и удаление.

## Связанные документы

- [Стандарт Windows-установщика](Windows_Installer_Standard.md)
- [Шаблон manifest schema 1](examples/appfleet-manifest.json)
