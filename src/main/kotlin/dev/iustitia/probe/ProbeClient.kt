package dev.iustitia.probe

import dev.iustitia.Iustitia
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text

/**
 * Client entrypoint: one tick wire, one command tree.
 *
 * START_CLIENT_TICK -> Iustitia.defer { Mirror.tick(); VlOut.poll() } — the defer queue
 * drains at the TOP of Iustitia.onClientTick, after this tick's movement and BEFORE
 * EntityTrackerManager.poll, so the mirror's server-state snapshot is current-tick and
 * the wiring is immune to mod registration order.
 *
 * /probe status · /probe on|off · /probe vl
 */
class ProbeClient : ClientModInitializer {

    override fun onInitializeClient() {
        ClientTickEvents.START_CLIENT_TICK.register {
            try {
                Iustitia.defer {
                    Mirror.tick()
                    VlOut.poll()
                }
            } catch (_: Throwable) {
                // fail-open: the probe must never break the tick
            }
        }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommandManager.literal("probe")
                    .then(ClientCommandManager.literal("on").executes {
                        Mirror.enabled = true
                        msg("§7mirror §aON§7 — Iustitia now tracks a copy of you (singleplayer).")
                        1
                    })
                    .then(ClientCommandManager.literal("off").executes {
                        Mirror.enabled = false
                        Mirror.removeMirror()
                        msg("§7mirror §coFF§7 — probe entity removed.")
                        1
                    })
                    .then(ClientCommandManager.literal("vl").executes {
                        VlOut.on = !VlOut.on
                        if (VlOut.on) {
                            VlOut.reset()
                            msg("§7VL stream §aON§7 — every flag on you prints below (existing deque first).")
                        } else {
                            msg("§7VL stream §cOFF§7.")
                        }
                        1
                    })
                    .executes { // bare /probe: status
                        val mc = MinecraftClient.getInstance()
                        val sp = mc.server != null && mc.world != null && mc.player != null
                        msg(
                            "§8[§dprobe§8] §7mirror=${state(Mirror.enabled)} " +
                                "spawned=${state(Mirror.isSpawned())} " +
                                "vlStream=${state(VlOut.on)} " +
                                "world=${if (sp) "§asingleplayer" else "§cnot in SP (no integrated server)"}"
                        )
                        msg("§7/probe on|off §8·§7 /probe vl §8·§7 flags print as §f[probe]§7 lines.")
                        1
                    }
            )
        }
    }

    private fun state(b: Boolean): String = if (b) "§aON" else "§cOFF"

    private fun msg(s: String) {
        try {
            MinecraftClient.getInstance().player?.sendMessage(Text.literal("§8[§dprobe§8] $s"), false)
        } catch (_: Throwable) {
        }
    }
}
