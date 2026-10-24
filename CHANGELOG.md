# Changelog

## 0.1.0

- Publish the public-world catalog as release 0.1.0 for all seven loader variants.
- Include the catalog layout, scrolling, Steam publication and Forge mod-list fixes from beta testing.
- Refresh bilingual documentation, credits and community links.

## 0.1.0-beta.18

- Add a draggable scrollbar and mouse-wheel scrolling to the portable catalog; preserve existing 26.x scrolling and Steam connection behavior.

- Remove the local catalog website and its HTTP listener from every runtime JAR.
- Fix Forge mod-list crashes by using a root-level logo filename instead of a resource path.
- Set mod-list authors to Kamilchik and K2 Studio on all seven loader variants.
- Validate the shared 512×512 Public Servers icon, loader metadata and absence of the local website in every release artifact.
- Include the tested public-world name and Steam publication fixes.

## 0.1.0-beta.17

- Resolve portable component factories by mapped method name; clear stale row widgets when the screen is initialized again.

- Remove unavailable `minecraft:friends/*` sprites from the catalog so Minecraft 26.1 renders its background, panels and tabs without magenta missing-texture blocks.
- Rebuild the portable Fabric/Quilt artifact for Minecraft 1.19–1.21.11 without the obsolete reflective text-rendering hook that crashed on 1.21.11.
- Убраны отсутствующие в Minecraft 26.1 спрайты `minecraft:friends/*`; фон, панели и вкладки каталога теперь рисуются без розовых заглушек.
- Пересобрана переносимая версия Fabric/Quilt для Minecraft 1.19–1.21.11: убран устаревший отражательный вызов отрисовки текста, из-за которого падал экран в 1.21.11.

## 0.1.0-beta.16

- Fix the Minecraft 26.1 rendering crash in the catalog tabs by resolving the GUI sprite pipeline at runtime, matching the catalog background and list rows.
- Исправлено падение Minecraft 26.1 при отрисовке вкладок каталога: спрайты вкладок используют совместимый выбор GUI-конвейера, как фон каталога и строки списка.

## 0.1.0-beta.15

- Fix the catalog screen crash on Minecraft 26.1 by resolving the GUI sprite
  pipeline API at runtime across 26.1 and 26.3.
- Keep opening and closing the catalog compatible with both screen APIs.
- Исправлено падение экрана каталога в Minecraft 26.1: API конвейера спрайтов
  теперь определяется для версий 26.1 и 26.3.
- Сохранена совместимость открытия и закрытия каталога между API экранов 26.1 и 26.3.

## 0.1.0-beta.13

- Added the e4steam Public Servers avatar to mod metadata and every loader JAR.
- Rebuilt Fabric/Quilt and NeoForge artifacts for Minecraft 26.3.
- Fixed opening and closing the catalog on Minecraft 26.1, where screen changes
  use a different API than 26.3.
- Добавлена иконка e4steam Public Servers в метаданные и JAR всех загрузчиков.
- Пересобраны артефакты Fabric/Quilt и NeoForge для Minecraft 26.3.
- Исправлено открытие и закрытие каталога в Minecraft 26.1 с учётом отличий API экранов.

## 0.1.0-beta.11

- Added Fabric/Quilt and NeoForge builds for Minecraft 26.3.
- Обновлены сборки Fabric/Quilt и NeoForge для Minecraft 26.3.

## 0.1.0-beta.10

- Updated loader dependencies for e4steam 0.3.2-beta.1.
- Public worlds now use the configured LAN capacity (2–64 players) instead of requiring exactly 8.
- Start publication immediately and refresh the open catalog every 10 seconds.
- Share overlapping native searches between refresh and join; allow another search after a failed request.
- Added tests covering publication, discovery, core join, removal and late callbacks.
- Исправлена зависимость от ядра: аддон загружается с e4steam 0.3.2-beta.1.
- В каталоге отображаются миры с лимитом от 2 до 64 игроков. Лимит берётся из настроек LAN.
- Публикация начинается сразу после открытия общего мира. Открытый каталог обновляется каждые 10 секунд.
- Одновременные обновление и подключение используют один поиск Steam. После ошибки поиск можно повторить.
- Проверка с двумя Steam-аккаунтами ещё требуется.

## 0.1.0-beta.9 — local Steam directory preview

- Discover and publish player-hosted public worlds directly through Steam matchmaking, without an HTTP registry. Requires the updated 0.3.3-steam-directory-dev core; Addon API remains 1.1.0.
- Keep publication separate from the game lobby, refresh advertisements every 10 seconds, expire them after 45 seconds, and leave late-created lobbies when a world closes.
- Retain Steam authentication, core admission checks and the 8-player world limit. Native dedicated-server discovery and remote moderation reports remain unavailable.
- Каталог общих миров переведён на Steam: отдельный сервер-каталог больше не нужен. Требуется новое ядро 0.3.3-steam-directory-dev; API остаётся 1.1.0.
- Добавлены проверки устаревших объявлений, закрытия и повторного открытия мира, запоздалого создания лобби. Подлинность Steam-пользователя и допуск в мир по-прежнему проверяет ядро.
- Выделенные серверы и отправка жалоб через этот Steam backend пока не реализованы. Проверка с двумя Steam-аккаунтами ещё требуется.

## 0.1.0-beta.8

- Register the Public Server access mode even before a directory is configured, so it remains visible in Open to LAN. Explain missing directory configuration or disabled publication in the confirmation dialog and reject public joins in those states.
- Пункт «Общий сервер» теперь виден в «Открыть для сети» и без настроенного каталога. Окно подтверждения объясняет отсутствие адреса или отключённую публикацию; вход в общем режиме в этих состояниях закрыт.
- This update does not configure a production registry or enable publication automatically.
- Обновление не настраивает рабочий каталог и не включает публикацию автоматически.

## 0.1.0-beta.7

- Increased the Minecraft 26.x catalog inset so the inner list and details frames no longer overlap the outer window frame.
- Увеличен внутренний отступ каталога Minecraft 26.x: рамки списка и сведений больше не заходят на внешнюю рамку окна.

## 0.1.0-beta.6

- Removed the multiplayer screen controls from behind the Minecraft 26.x catalog overlay; only the native dimmed background and the catalog panel are rendered.
- Убраны элементы экрана сетевой игры из-под окна каталога Minecraft 26.x; теперь отображаются только нативный затемнённый фон и панель каталога.

## 0.1.0-beta.5

- Fixed a Minecraft 26.2 client crash when opening Public Servers from the multiplayer screen: the catalog no longer requests a second blur pass in the same frame.
- Исправлен вылет клиента Minecraft 26.2 при открытии «Публичных серверов» из сетевой игры: каталог больше не запрашивает повторное размытие в одном кадре.

## 0.1.0-beta.4

### English

- Added Fabric/Quilt builds for Minecraft 1.17–1.18.2 and 1.19–1.21.11.
- Added Forge builds for Minecraft 1.17.1–1.18.1 and 1.18.2–1.20.2.
- Added a NeoForge build for Minecraft 1.20.2–1.21.11 and widened the native 26.x builds to 26.1–26.2.
- Added a Java 16/17 portable native-widget screen without direct references to the Minecraft 26.x GUI classes.
- Kept the full Friends-style textured screen for Minecraft 26.x.
- Added synchronized version checks, bytecode/linkage audits and a seven-JAR release collection task.
- Made registry HTTP client shutdown compatible with Java 16 while preserving explicit cleanup on newer JDKs.
- Added explicit Mixin 0.8 metadata to every runtime JAR.
- Added resource-pack metadata to Forge and NeoForge JARs and made the artifact audit require it.
- Smoke-tested one client profile for each of the seven runtime JARs.

### Русский

- Добавлены сборки Fabric/Quilt для Minecraft 1.17–1.18.2 и 1.19–1.21.11.
- Добавлены сборки Forge для Minecraft 1.17.1–1.18.1 и 1.18.2–1.20.2.
- Добавлена сборка NeoForge для Minecraft 1.20.2–1.21.11, а нативные сборки 26.x расширены до 26.1–26.2.
- Добавлен переносимый экран на ванильных виджетах с Java 16/17 без прямых ссылок на GUI-классы Minecraft 26.x.
- Для Minecraft 26.x сохранён полный текстурированный экран в стиле Friends.
- Добавлены проверка синхронности версии, аудит байткода и ссылок, а также сборка комплекта из семи JAR.
- Завершение HTTP-клиента реестра сделано совместимым с Java 16 с явной очисткой ресурсов на новых JDK.
- Во все JAR добавлено явное требование Mixin 0.8.
- В сборки Forge и NeoForge добавлен `pack.mcmeta`, а его наличие теперь проверяет аудит артефактов.
- Для каждого из семи JAR выполнен контрольный запуск клиента.

## 0.1.0-beta.3

### English

- Fixed NeoForge rejecting the stable e4steam 0.3.2 release because the addon still required the `0.3.2-dev` qualifier.
- The loader dependency now accepts stable e4steam 0.3.2 and newer releases.

### Русский

- Исправлен отказ NeoForge запускаться со стабильным e4steam 0.3.2 из-за оставшегося требования `0.3.2-dev`.
- Теперь зависимость загрузчика принимает стабильный e4steam 0.3.2 и более новые версии.

## 0.1.0-beta.2

### English

- Rebuilt the catalog around the native Minecraft 26.2 Friends panel and tab textures.
- Added separate All Servers, Player Worlds and Dedicated tabs.
- Improved adaptive layout, selected-server details, empty states and scrollbar rendering.
- Switched the Addon API 1.1 compile-only dependency to Maven Central.

### Русский

- Каталог переведён на оригинальные текстуры панели и вкладок Friends из Minecraft 26.2.
- Добавлены отдельные вкладки «Все серверы», «Миры игроков» и «Выделенные».
- Улучшены адаптивная компоновка, карточка выбранного сервера, пустые состояния и полоса прокрутки.
- Compile-only зависимость Addon API 1.1 переведена на Maven Central.

## 0.1.0-beta.1

### English

- Added the Fabric and NeoForge 26.2 addon builds.
- Added a unified catalog for integrated worlds and dedicated servers.
- Added search, filters, compatibility details, join cancellation and safe empty/error states.
- Added the confirmed Public Server access mode, lifecycle-driven publication and lease cleanup.
- Added a standalone SQLite registry, strict JSON contract, pagination, rate limits, reports and moderation.
- Added Addon API 1.1 public-directory integration without direct Steamworks or internal e4steam calls.
- Added English/Russian localization, unit tests and distributable-JAR audits.

### Русский

- Добавлены сборки аддона для Fabric и NeoForge 26.2.
- Добавлен единый каталог миров игроков и выделенных серверов.
- Добавлены поиск, фильтры, сведения о совместимости, отмена подключения и безопасные состояния ошибок.
- Добавлены подтверждаемый режим «Публичный сервер», публикация по событиям e4steam и очистка аренды.
- Добавлен отдельный SQLite-реестр со строгим JSON, пагинацией, лимитами запросов, жалобами и модерацией.
- Добавлена интеграция через Public Directory в Addon API 1.1 без прямых вызовов Steamworks и внутренних классов e4steam.
- Добавлены английская и русская локализация, тесты и аудит JAR-файлов.
