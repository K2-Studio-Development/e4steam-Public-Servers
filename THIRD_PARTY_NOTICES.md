# Third-party notices

The runtime addon JARs contain Public Servers code and artwork from this repository. They do not bundle e4steam core/API classes, Steamworks, native libraries or Minecraft assets.

## e4steam Addon API

[e4steam Addon API](https://github.com/Kamilhik/e4steam) is a compile-only dependency supplied at runtime by the installed e4steam core. Consult the core repository for its license and dependency notices.

## Loaders and build tooling

The project compiles against Fabric Loader, Forge and NeoForge and uses Gradle, Fabric Loom, Architectury Loom and ModDevGradle. These tools and loader classes are not repackaged into the addon runtime JARs. Their own distributions include their applicable license notices.

## Tests

JUnit is used for automated tests and is not bundled in runtime addon JARs.

## Optional registry backend

The separate registry-server distribution depends on [Xerial SQLite JDBC](https://github.com/xerial/sqlite-jdbc). Its dependency JAR retains its upstream license and notices. This backend distribution is separate from the Minecraft addon JARs.

## Trademarks and game resources

Minecraft is a trademark of Microsoft/Mojang Studios. Steam is a trademark of Valve Corporation. Game textures are referenced from the installed Minecraft resources rather than redistributed here. This project is not affiliated with Microsoft, Mojang Studios or Valve.
