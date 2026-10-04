package dev.iustitia.probe.mixin

import dev.iustitia.Iustitia
import dev.iustitia.event.SwingSignal
import dev.iustitia.probe.Mirror
import net.minecraft.entity.LivingEntity
import net.minecraft.network.packet.s2c.play.EntityAnimationS2CPacket
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerChunkManager
import net.minecraft.util.Hand
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * The server-side swing source: `LivingEntity.swingHand(Hand, boolean)` builds the
 * `EntityAnimationS2CPacket` only when the swing passes its re-arm gate, then sends it
 * via `sendToOtherNearbyPlayers` (the C2S swing path) or `sendToNearbyPlayers` (a forced
 * swing) — both seen by other clients. Hooking the two call sites (not method HEAD) makes
 * signal volume and timing equal the real observer stream: a suppressed swing publishes
 * nothing. Filtered to self via [Mirror.selfUuid]; fail-open; `defaultRequire: 1` makes a
 * drifted bytecode shape a loud launch failure instead of a silent no-op.
 */
@Mixin(LivingEntity::class)
class LivingEntitySwingBroadcastMixin {

    @Inject(
        method = ["swingHand(Lnet/minecraft/util/Hand;Z)V"],
        at = [At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/world/ServerChunkManager;sendToOtherNearbyPlayers(Lnet/minecraft/entity/Entity;Lnet/minecraft/network/packet/Packet;)V",
        )],
    )
    private fun probe_onSwingToOthers(hand: Hand, force: Boolean, ci: CallbackInfo) = publish(hand)

    @Inject(
        method = ["swingHand(Lnet/minecraft/util/Hand;Z)V"],
        at = [At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/world/ServerChunkManager;sendToNearbyPlayers(Lnet/minecraft/entity/Entity;Lnet/minecraft/network/packet/Packet;)V",
        )],
    )
    private fun probe_onSwingToNearby(hand: Hand, force: Boolean, ci: CallbackInfo) = publish(hand)

    private fun publish(hand: Hand) {
        try {
            val self = Mirror.selfUuid ?: return
            // Kotlin mixin: the class does not extend the target at compile time, so cast
            // through Any — at runtime `this` IS the ServerPlayerEntity when we are applied.
            val player = ((this as Any) as? ServerPlayerEntity) ?: return
            if (player.getUuid() != self) return
            Iustitia.bus.publish(
                SwingSignal(
                    attacker = self,
                    tick = Iustitia.tickCounter,
                    nanoTime = System.nanoTime(),
                    animationId = if (hand == Hand.MAIN_HAND) EntityAnimationS2CPacket.SWING_MAIN_HAND
                    else EntityAnimationS2CPacket.SWING_OFF_HAND,
                )
            )
        } catch (_: Throwable) {
            // fail-open
        }
    }
}
