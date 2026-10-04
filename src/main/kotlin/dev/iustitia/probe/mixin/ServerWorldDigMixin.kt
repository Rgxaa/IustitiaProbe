package dev.iustitia.probe.mixin

import dev.iustitia.Iustitia
import dev.iustitia.event.DiggingSignal
import dev.iustitia.probe.Mirror
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * The server-side dig source: `ServerWorld.setBlockBreakingInfo(entityId, pos, progress)`
 * IS the broadcast — vanilla calls it only when the integer stage CHANGES, and passes -1
 * for abort/stop/destroy, so forwarding it verbatim for [Mirror.selfEntityId] reproduces
 * exactly what an observer receives, cadence included. One int compare before any
 * allocation; fail-open; `defaultRequire: 1` = loud failure on bytecode drift.
 */
@Mixin(ServerWorld::class)
class ServerWorldDigMixin {

    @Inject(method = ["setBlockBreakingInfo(ILnet/minecraft/util/math/BlockPos;I)V"], at = [At("HEAD")])
    private fun probe_onBlockBreakingInfo(entityId: Int, pos: BlockPos, progress: Int, ci: CallbackInfo) {
        try {
            if (entityId != Mirror.selfEntityId) return
            val uuid = Mirror.selfUuid ?: return
            Iustitia.bus.publish(DiggingSignal(uuid, Iustitia.tickCounter, progress, pos))
        } catch (_: Throwable) {
            // fail-open
        }
    }
}
