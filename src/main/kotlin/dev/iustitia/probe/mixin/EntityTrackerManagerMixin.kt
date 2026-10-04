package dev.iustitia.probe.mixin

import dev.iustitia.probe.Mirror
import net.minecraft.client.network.AbstractClientPlayerEntity
import net.minecraft.client.world.ClientWorld
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Redirect

/**
 * The probe's one injection into Iustitia: `EntityTrackerManager.poll` reads
 * "everyone else" from exactly one call — `ClientWorld.getPlayers()` feeding its
 * for-each (verified with javap). This @Redirect appends the mirror to that list.
 *
 * The mirror itself is never in world storage, so rendering/collision/crosshair/target
 * queries never see it (see Mirror), while the tracker's per-entity field reads and
 * ground queries (poll's real-world argument) work unchanged. `seen` then contains the
 * mirror's uuid, so the despawn sweep and `Check.purge` lifecycle stay correct; when the
 * probe is off the redirect returns the untouched list. Fail-open: any error returns the
 * original list rather than breaking poll; `defaultRequire: 1` = loud failure on drift.
 */
@Mixin(value = [dev.iustitia.tracking.EntityTrackerManager::class])
class EntityTrackerManagerMixin {

    @Redirect(
        method = ["poll(Lnet/minecraft/client/world/ClientWorld;I)Ljava/util/List;"],
        at = At(value = "INVOKE", target = "Lnet/minecraft/client/world/ClientWorld;getPlayers()Ljava/util/List;"),
    )
    private fun probe_augmentPolledPlayers(world: ClientWorld): List<AbstractClientPlayerEntity> {
        val real = world.players
        return try {
            val mirror = Mirror.entityForPoll(world) ?: return real
            if (real.any { it === mirror }) real else real + mirror
        } catch (_: Throwable) {
            real // fail-open: never let probe bookkeeping break poll
        }
    }
}
