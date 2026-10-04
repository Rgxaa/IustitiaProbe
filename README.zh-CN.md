# Iustitia Probe

一个配合 [Iustitia](https://github.com/ThoriaDevelopment/Iustitia) 使用的非官方第三方开发辅助模组，在单人游戏里把你本人镜像进它的检测管线。

纯客户端。Minecraft 1.21.11、Fabric、Kotlin。[English](README.md) · 中文

## 工作原理

- `Mirror.kt` 构造一个 `OtherClientPlayerEntity` 副本，并且从不把它加进世界，所以渲染、碰撞和准星锁定都看不见它。每 tick 从集成服务器的 `ServerPlayerEntity` 复制位置、视角、速度和装备。
- `EntityTrackerManagerMixin` 把这个镜像追加进 `EntityTrackerManager.poll` 遍历的那个列表。探针只碰 Iustitia 这一行。
- `LivingEntitySwingBroadcastMixin` 和 `ServerWorldDigMixin` 从服务端自己的广播调用点发出 `SwingSignal` 和 `DiggingSignal`，走 `Iustitia.bus`。

三个 mixin，`defaultRequire: 1`，全程 fail-open，不发包，不写文件。

## 命令

| 命令 | 作用 |
|---|---|
| `/probe` | 状态 |
| `/probe on` | 打开镜像（单人游戏默认开启） |
| `/probe off` | 关闭镜像 |
| `/probe vl` | 把记录在你身上的每条 flag 打进聊天 |

## 构建

```bash
./gradlew build         # build/libs/iustitia-probe-0.1.0.jar
./gradlew runClient     # 开发客户端（已加载探针）
```

需要 JDK 21。Iustitia 本身就是普通依赖：Gradle 从 Modrinth Maven 拉取 `maven.modrinth:iustitia`，Loom 再像处理任意模组 jar 一样重映射。版本在 `gradle.properties` 的 `iustitia_version` 里固定。

## 限制

仅限单人游戏：在远程服务器上镜像会被拆除。需要受害者的检测在单人世界里无人可判。创造模式玩家被豁免，所以用生存模式测试。`/probe vl` 读取每个玩家最近 50 条 flag。

## 许可证

MIT，见 [LICENSE](LICENSE)。
