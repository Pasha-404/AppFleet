# Структура проекта AppFleet

## План разработки

1. **Основание** — Gradle Wrapper, зафиксированные зависимости, базовая сборка, Git и документация.
2. **Домен и GitHub** — модели, SemVer, нормализация URL, проверка manifest, выбор asset, GitHub REST-клиент с ETag/rate limit и атомарное хранилище JSON.
3. **Безопасные операции** — проверка SHA-256/Authenticode, закрытие процесса, EXE/MSI/ZIP-установка с откатом ZIP, обнаружение установленной версии и журналирование.
4. **Интерфейс и сценарии** — JavaFX-таблица, журнал, добавление, подтверждение, скачивание/отмена, обновление AppFleet раньше проверки списка.
5. **Поставка** — jlink/jpackage app-image, Inno Setup, SHA-256/manifest, CI, тесты и реальная проверка GitHub API с SortIt.

## Каталоги исходного кода

```text
src/main/java/ru/pashaapps/appfleet/
  domain/        неизменяемые модели и правила выбора
  github/        GitHub REST API и загрузка asset
  persistence/   атомарные JSON-хранилища и пути приложения
  install/       проверка файлов, реестр, процессы и пакетные операции
  service/       оркестрация проверок/обновлений и журнал
  ui/            JavaFX-представления и диалоги
src/test/java/   unit и fixture-интеграционные тесты
src/test/resources/fixtures/  подготовленные ответы API GitHub
installer/       Inno Setup-шаблон
scripts/         воспроизводимая сборка Windows-релиза
.github/workflows/  сборка release-артефактов
```

Данные пользователя не попадают в каталог установки: `%APPDATA%\\PashaApps\\AppFleet` содержит настройки и список репозиториев; `%LOCALAPPDATA%\\PashaApps\\AppFleet` — логи и кэш; временные операции располагаются в `%TEMP%\\AppFleet`.

