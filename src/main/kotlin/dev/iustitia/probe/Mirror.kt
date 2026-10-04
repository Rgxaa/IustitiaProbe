package dev.iustitia.probe

import com.mojang.authlib.GameProfile
import dev.iustitia.Iustitia
import dev.iustitia.event.EffectSignal
import dev.iustitia.event.HurtSignal
import dev.iustitia.event.HurtSource
import dev.iustitia.event.VelocitySignal
import dev.iustitia.protocol.ProtocolDetector
import dev.iustitia.tracking.EntityTrackerManager
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.OtherClientPlayerEntity
import net.minecraft.client.world.ClientWorld
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.item.ItemStack
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.Hand
import java.util.UUID

/**
 * The self-mirror: an [OtherClientPlayerEntity] copy of you, fed every tick from the
 * integrated server's [ServerPlayerEntity] — the exact state the server would broadcast
 * to other clients (so blink/freeze cheats freeze it too; the C2S stream is never read).
 *
 * It is **never added to a world**: [mixin.EntityTrackerManagerMixin] appends it to the
 * one list `EntityTrackerManager.poll` iterates, so rendering, collision, crosshair and
 * Meteor-style target queries (all world-storage backed) never see it — while the
 * tracker's field reads and ground queries (poll's real-world argument) work unchanged.
 *
 * Swing/dig signals come from server-side broadcast hooks (mixin/); hurt/velocity/effect
 * signals are synthesized from server state here — all through [Iustitia.bus], the
 * packet mixin's own path. Sync runs via [Iustitia.defer] from START_CLIENT_TICK, so it
 * drains after this tick's movement, BEFORE the poll. Fail-open throughout.
 */
object Mirror {

    /**
     * Fixed entity id: ctor-assigned ids share a client-side counter unrelated to
     * server-assigned packet ids, so a fresh mirror could collide with a real entity's
     * id and poison `EntityTrackerManager.byEntityId` lookups. The server never
     * names `MAX-7`.
     */
    private const val MIRROR_ENTITY_ID = Int.MAX_VALUE - 7

    /** Your uuid — read by the SERVER-thread mixins as the "is this me?" filter. */
    @Volatile
    var selfUuid: UUID? = null
        internal set

    /** Your server-side entity id — the dig mixin's early-out filter (one int compare). */
    @Volatile
    var selfEntityId: Int = -1
        internal set

    @Volatile
    var enabled: Boolean = true

    private var mirror: OtherClientPlayerEntity? = null
    private var spawnedIn: ClientWorld? = null

    // Edge baselines (client-thread only, inside tick()).
    private var prevHurtTime = 0
    private var prevSpeedAmp = -1
    private var prevBlindAmp = -1

    fun tick() {
        try {
            val mc = MinecraftClient.getInstance()
            val player = mc.player ?: return teardown()
            val world = mc.world ?: return teardown()
            // Singleplayer only: on a remote server you already have real other players.
            val server = mc.server ?: return teardown()
            val sp = server.playerManager.getPlayer(player.uuid) ?: return teardown()

            selfUuid = player.uuid
            selfEntityId = sp.id

            if (!enabled) {
                removeMirror()
                return
            }

            ensureMirror(world, player.name.string, sp)
            val m = mirror ?: return

            // --- ticked state: straight off the server's copy of you -----------------
            m.setPosition(sp.getX(), sp.getY(), sp.getZ())
            m.yaw = sp.getYaw()
            m.pitch = sp.getPitch()
            m.bodyYaw = sp.bodyYaw
            m.lastBodyYaw = sp.lastBodyYaw
            m.headYaw = sp.headYaw
            m.lastHeadYaw = sp.lastHeadYaw
            m.setOnGround(sp.isOnGround)
            m.setSprinting(sp.isSprinting)
            m.setSneaking(sp.isSneaking)
            m.setSwimming(sp.isSwimming)
            m.setVelocity(sp.getVelocity())
            if (sp.isGliding != m.isGliding) {
                if (sp.isGliding) m.startGliding() else m.stopGliding()
            }

            // equipment (held item feeds the tracker's use-action / blocking reads)
            for (slot in EquipmentSlot.values()) {
                val want = sp.getEquippedStack(slot)
                if (!ItemStack.areEqual(m.getEquippedStack(slot), want)) {
                    m.equipStack(slot, want.copy())
                }
            }

            // using-item bit: setCurrentHand only sets activeItemStack on a client-side
            // entity, so write the tracked bit reflectively — the byte the server
            // rebroadcasts in metadata.
            val using = sp.isUsingItem
            if (using != m.isUsingItem) {
                if (using) m.setCurrentHand(Hand.MAIN_HAND) else m.stopUsingItem()
                setLivingFlag(m, using)
            }

            // --- event-shaped state: what the server would broadcast -----------------
            publishHurtEdge(sp)
            publishEffectEdges(sp)
        } catch (_: Throwable) {
            // fail-open: a mirror glitch must never crash the tick
        }
    }

    /**
     * The ONLY consumer of the mirror: the @Redirect in [mixin.EntityTrackerManagerMixin].
     * Null when disabled or when [world] is not the world the mirror was built for (a
     * dimension change between sync and poll drops it for one tick; the despawn sweep
     * then resets it and the next sync rebuilds).
     */
    fun entityForPoll(world: ClientWorld): OtherClientPlayerEntity? {
        if (!enabled) return null
        return mirror?.takeIf { spawnedIn === world }
    }

    private fun ensureMirror(world: ClientWorld, name: String, sp: ServerPlayerEntity) {
        val existing = mirror
        if (existing != null && spawnedIn === world && !existing.isRemoved) return
        removeMirror()
        // Built against the real world (box raycasts resolve correct blocks) but never
        // registered with it; id pinned out of the server-id range (MIRROR_ENTITY_ID).
        val m = OtherClientPlayerEntity(world, GameProfile(sp.getUuid(), name))
        m.setPosition(sp.getX(), sp.getY(), sp.getZ())
        m.setOnGround(sp.isOnGround)
        m.setId(MIRROR_ENTITY_ID)
        mirror = m
        spawnedIn = world
        // Baselines: a joining observer sees no retroactive hurt/effect history — an
        // effect already running at join is never published, exactly as for an observer
        // who arrived after the potion was drunk.
        prevHurtTime = sp.hurtTime
        prevSpeedAmp = -1
        prevBlindAmp = -1
    }

    private fun publishHurtEdge(sp: ServerPlayerEntity) {
        val now = sp.hurtTime
        val prev = prevHurtTime
        prevHurtTime = now
        if (now <= prev) return // hurtTime counts down 10..0; a rise = a new hit
        val uuid = selfUuid ?: return
        val tick = Iustitia.tickCounter
        val vel = sp.getVelocity()
        // Damage packet first, knockback second — the order a real observer gets them.
        // markVelocity has no bus subscription, so it is called by hand, like the packet
        // mixin does.
        Iustitia.bus.publish(HurtSignal(uuid, tick, -1, HurtSource.ENTITY_DAMAGE))
        Iustitia.bus.publish(VelocitySignal(uuid, tick, vel))
        Iustitia.defer { EntityTrackerManager.markVelocity(uuid, tick, vel) }
        ProtocolDetector.noteDamagePacket() // we stand in for an EntityDamageS2CPacket
    }

    private fun publishEffectEdges(sp: ServerPlayerEntity) {
        val uuid = selfUuid ?: return
        var speedAmp = -1
        var blindAmp = -1
        for (eff in sp.statusEffects) {
            val type = eff.effectType
            if (type === StatusEffects.SPEED) speedAmp = eff.amplifier
            else if (type === StatusEffects.BLINDNESS) blindAmp = eff.amplifier
        }
        val tick = Iustitia.tickCounter
        if (speedAmp != prevSpeedAmp) {
            val added = speedAmp >= 0
            val amp = if (added) speedAmp else -1
            // Double action, exactly like the packet mixin: markEffect has no bus subscription.
            Iustitia.defer { EntityTrackerManager.markEffect(uuid, true, amp, added) }
            Iustitia.bus.publish(EffectSignal(uuid, tick, true, amp, added = added))
            prevSpeedAmp = speedAmp
        }
        if (blindAmp != prevBlindAmp) {
            val added = blindAmp >= 0
            val bAmp = if (added) blindAmp else -1
            Iustitia.bus.publish(
                EffectSignal(
                    entity = uuid, tick = tick, isSpeed = false, speedAmplifier = -1,
                    isBlind = true, blindAmplifier = bAmp, added = added,
                )
            )
            prevBlindAmp = blindAmp
        }
    }

    /** Drop the mirror (toggle off / world change / disconnect). */
    fun removeMirror() {
        mirror = null
        spawnedIn = null
    }

    private fun teardown() {
        removeMirror()
        selfUuid = null
        selfEntityId = -1
    }

    /**
     * Write the `LivingEntity` USING_ITEM tracked bit (protected — reflected, same trick
     * as Iustitia's gametest harness): the byte the server rebroadcasts in metadata and
     * the byte `isUsingItem()` reads back. Client thread, failure swallowed.
     */
    private fun setLivingFlag(e: LivingEntity, on: Boolean) {
        try {
            val m = LivingEntity::class.java.getDeclaredMethod(
                "setLivingFlag", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            )
            m.isAccessible = true
            m.invoke(e, 1, on) // 1 == USING_ITEM flag (2 == OFF_HAND)
        } catch (_: Throwable) {
        }
    }

    fun isSpawned(): Boolean = mirror != null && spawnedIn != null
}
