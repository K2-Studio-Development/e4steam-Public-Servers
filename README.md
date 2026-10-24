<div align="center">

<img src="addon-fabric/src/client/resources/assets/e4steam_public_servers/icon.png" width="180" alt="e4steam Public Servers">

# e4steam Public Servers

### Find public e4steam worlds directly in Minecraft
### Общие миры e4steam прямо в Minecraft

**By Kamilchik · K2 Studio**

[Download](https://github.com/K2-Studio-Development/e4steam-Public-Servers/releases) · [e4steam](https://github.com/Kamilhik/e4steam) · [Issues and suggestions](https://github.com/K2-Studio-Development/e4steam-Public-Servers/issues)

🇬🇧 English · [🇷🇺 Русский](#русский)

</div>

## Community and support

- [Personal Telegram — @Kamilchikm](https://t.me/Kamilchikm)
- [K2 Studio Telegram — @K2Studio_Dev](https://t.me/K2Studio_Dev)
- [K2 Studio Discord](https://discord.gg/zFvrHz2ys7)
- [Support development](https://dalink.to/kamilchik1231)
- [e4steam on CurseForge](https://www.curseforge.com/minecraft/mc-mods/e4steam)
- [e4steam on Modrinth](https://modrinth.com/project/SqqdJF90)
- [Video demonstration](https://youtu.be/KJ1W_eJ2VK4)
- [e4steam Friends addon](https://github.com/K2-Studio-Development/e4steam-Friends)

## Features

- An in-game Public Servers catalog with player worlds and a separate dedicated-server tab.
- Steam discovery for integrated worlds, without a website, local web server, domain or API key.
- Editable public server name in Open to LAN; the default is `<host nickname> server`.
- Search, compatibility and available-slot filters, player counts and automatic refresh.
- A draggable scrollbar and mouse-wheel scrolling for long lists; hidden when every result fits.
- English and Russian interface, with portable screens for older Minecraft releases.
- Steam authentication and admission checks remain controlled by e4steam.

The default Steam catalog currently publishes integrated player worlds. Dedicated-server publication requires the separate registry backend; it is not implemented by the default Steam discovery path. The dedicated tab can therefore be empty.

## Installation

Install a matching **e4steam 0.3.2-beta.1 or newer compatible core with Addon API 1.1** and the Public Servers addon. Use the latest matching core build containing the tested LAN and Steam fixes. Steam must be running and signed in on each player's computer.

| Minecraft | Loader | Java minimum | JAR marker |
| --- | --- | ---: | --- |
| 1.17–1.18.2 | Fabric / Quilt | 16 | `fabric-quilt-mc1.17-1.18.2` |
| 1.17.1–1.18.1 | Forge | 16 | `forge-mc1.17.1-1.18.1` |
| 1.19–1.21.11 | Fabric / Quilt | 17 | `fabric-quilt-mc1.19-1.21.11` |
| 1.18.2–1.20.2 | Forge | 17 | `forge-mc1.18.2-1.20.2` |
| 1.20.2–1.21.11 | NeoForge | 17 | `neoforge-mc1.20.2-1.21.11` |
| 26.1–26.3 | Fabric / Quilt | 25 | `fabric-quilt-mc26.1-26.3` |
| 26.1–26.3 | NeoForge | 25 | `neoforge-mc26.1-26.3` |

The table lists the minimum class-file requirement of each addon build. Minecraft and the installed loader can require a newer Java version, especially within the broad 1.19–1.21.11 range.

1. Download the addon and core JARs for the same Minecraft version and loader.
2. Put both files in `mods`. Install the matching Fabric API or Quilted Fabric API where required.
3. Host: open a world → Open to LAN → Steam access → **Public Server**, confirm publication and enter the server name.
4. Guest: open Multiplayer → **Public Servers**, select the world and connect.

Friends-only and invitation-only worlds are not published. The list refreshes every 10 seconds; stale advertisements expire after 45 seconds. The LAN player limit includes the host.

## Build

JDK 17 and JDK 25 are needed to build all seven targets.

```sh
./gradlew verification releaseArtifactAudit --no-configuration-cache
```

On Windows, use `gradlew.bat`. Runtime artifacts are collected in `build/release/`. The audit checks loader metadata, authors, the shared icon, supported bytecode and the absence of the removed local website. The compile-only Addon API is resolved from Maven Central and is not bundled into runtime JARs.

## Verification

Version **0.1.0-beta.18** removes the temporary local catalog website from every runtime artifact and updates all loader credits to **Kamilchik** and **K2 Studio**. Automated checks cover the domain, client, backend and all seven distributables. In-game coverage and remaining multiplayer checks are recorded in [Testing](docs/TESTING.md); a successful build does not replace testing every game version with two Steam accounts.

## Development

- [Architecture](docs/ARCHITECTURE.md)
- [Steam discovery](docs/STEAM_DIRECTORY.md)
- [Protocol](docs/REGISTRY_PROTOCOL.md)
- [Security](docs/SECURITY.md) and [Privacy](docs/PRIVACY.md)
- [Development](docs/DEVELOPMENT.md) and [Deployment](docs/DEPLOYMENT.md)
- [Changelog](CHANGELOG.md) and [Third-party notices](THIRD_PARTY_NOTICES.md)

The optional HTTP registry is a separate development/deployment component. It is not the removed local status website and does not start an HTTP listener inside the addon. See the deployment guide for explicit registry configuration.

## License

[Apache License 2.0](LICENSE). Minecraft is a trademark of Microsoft/Mojang Studios; Steam is a trademark of Valve Corporation. This project is not affiliated with either company.

---

## Русский

**e4steam Public Servers** — аддон от **Kamilchik** и **K2 Studio** со списком общих миров прямо в Minecraft.

### Возможности

- Каталог общих миров через Steam, поиск, фильтры совместимости и свободных мест.
- Имя общего сервера в окне «Открыть для сети», по умолчанию — `<ник хоста> server`.
- Количество игроков, автоматическое обновление, русский и английский интерфейс.
- Полоса прокрутки с перетаскиванием и колесом мыши для длинного списка.
- Сборки для Fabric, Quilt, Forge и NeoForge от Minecraft 1.17 до 26.3.
- Для обычного каталога не нужны сайт, локальный веб-сервер, домен или API-ключ.

Через стандартный механизм Steam сейчас публикуются миры игроков. Выделенные серверы требуют отдельного серверного каталога; стандартный Steam-поиск их не публикует, поэтому эта вкладка может быть пустой.

### Установка и игра

1. Установи совместимое ядро **e4steam 0.3.2-beta.1+ с Addon API 1.1** и аддон для своей версии Minecraft и загрузчика. Рекомендуется свежая сборка ядра с исправлениями LAN и Steam. Таблица совместимости находится выше.
2. Положи оба JAR в `mods`. Для Fabric/Quilt установи подходящий Fabric API/Quilted Fabric API.
3. Запусти Steam и войди в аккаунт на обоих компьютерах.
4. Хост: открой мир для сети, выбери **«Общий сервер»**, подтверди публикацию и задай имя.
5. Гость: открой **«Сетевая игра» → «Общие серверы»**, выбери мир и подключись.

Обновление каталога — каждые 10 секунд. Объявления без обновлений исчезают через 45 секунд. Миры только для друзей и по приглашению не публикуются. Лимит игроков учитывает хоста.

### Сборка и проверка

```sh
./gradlew verification releaseArtifactAudit --no-configuration-cache
```

Нужны JDK 17 и JDK 25. На Windows используй `gradlew.bat`. Все семь JAR находятся в `build/release/`. Версия **0.1.0-beta.18** не содержит временного сайта статуса и его HTTP-сервера. Подтверждённые проверки в игре перечислены в [TESTING.md](docs/TESTING.md).

### Сообщество и поддержка

- [Личный Telegram — @Kamilchikm](https://t.me/Kamilchikm)
- [Telegram K2 Studio](https://t.me/K2Studio_Dev)
- [Discord K2 Studio](https://discord.gg/zFvrHz2ys7)
- [Поддержать разработку](https://dalink.to/kamilchik1231)
- [Скачать](https://github.com/K2-Studio-Development/e4steam-Public-Servers/releases)
- [Сообщить об ошибке](https://github.com/K2-Studio-Development/e4steam-Public-Servers/issues)

Лицензия — [Apache 2.0](LICENSE). Полная документация и ссылки на CurseForge, Modrinth, видео и e4steam Friends находятся выше.
