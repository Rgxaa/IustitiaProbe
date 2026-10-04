# Iustitia Probe

An unofficial third-party dev helper for use with [Iustitia](https://github.com/ThoriaDevelopment/Iustitia) that mirrors your own player into its detection pipeline in singleplayer.

Client side only. Minecraft 1.21.11, Fabric, Kotlin. English · [中文](README.zh-CN.md)

## How it works

- `Mirror.kt` builds an `OtherClientPlayerEntity` copy of you and never adds it to a world, so rendering, collision and targeting never see it. Each tick it copies position, look, velocity and equipment from the integrated server's `ServerPlayerEntity`.
- `EntityTrackerManagerMixin` appends that mirror to the list `EntityTrackerManager.poll` iterates. That is the only line of Iustitia the probe touches.
- `LivingEntitySwingBroadcastMixin` and `ServerWorldDigMixin` publish `SwingSignal` and `DiggingSignal` from the server's own broadcast call sites, through `Iustitia.bus`.

Three mixins, `defaultRequire: 1`, fail-open, no packets sent, no files written.

## Commands

| command | effect |
|---|---|
| `/probe` | status |
| `/probe on` | mirror on (the default in singleplayer) |
| `/probe off` | mirror off |
| `/probe vl` | print every flag recorded on you into chat |

## Build

```bash
./gradlew build         # build/libs/iustitia-probe-0.1.0.jar
./gradlew runClient     # dev client with the probe loaded
```

JDK 21. Iustitia itself is a normal dependency: Gradle pulls `maven.modrinth:iustitia` from Modrinth Maven, and Loom remaps it like any other mod jar. The version lives in `gradle.properties` (`iustitia_version`).

## Limitations

Singleplayer only: on a remote server the mirror tears down. Checks that need a victim player have nobody to act on in a solo world. Creative players are exempt, so test in survival. `/probe vl` reads the last 50 flags per player.

## License

MIT, see [LICENSE](LICENSE).
