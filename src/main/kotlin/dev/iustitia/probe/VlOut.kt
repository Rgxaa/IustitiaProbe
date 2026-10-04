package dev.iustitia.probe

import dev.iustitia.Iustitia
import dev.iustitia.NumFmt
import dev.iustitia.history.FlagHistory

/**
 * `/probe vl` — stream every violation recorded on your mirror into chat, including the
 * sub-setback ones a chat alert never shows. No Iustitia injection: [FlagHistory.recordFlag]
 * is written unconditionally by `Check.flag` and [FlagHistory.flags] is its public reader;
 * this polls the deque from the same pre-poll defer block as [Mirror.tick] and prints
 * entries not yet printed (identity = tick|checkId|label|vl — unique per flag). On enable
 * it also prints what is already in the deque, so enabling mid-fight doesn't start blind.
 */
object VlOut {

    @Volatile
    var on: Boolean = false

    /** Fingerprints already printed; capped so a long session can't grow it unbounded. */
    private val printed = LinkedHashSet<String>()

    fun poll() {
        if (!on) return
        val uuid = Mirror.selfUuid ?: return
        try {
            val flags = FlagHistory.flags(uuid) // newest-first
            val fresh = flags.filterNot { fp(it) in printed }
            if (fresh.isEmpty()) return
            for (f in fresh.asReversed()) { // chronological: oldest new flag first
                print(f)
                printed.add(fp(f))
            }
            while (printed.size > 256) {
                val it = printed.iterator()
                it.next()
                it.remove()
            }
        } catch (_: Throwable) {
            // fail-open: the stream is diagnostics, never a crash path
        }
    }

    private fun fp(f: FlagHistory.Flag): String = "${f.tick}|${f.checkId}|${f.label}|${f.vl}"

    private fun print(f: FlagHistory.Flag) {
        val setback = Iustitia.allChecks.firstOrNull { it.id == f.checkId }?.setbackVL
        val why = f.evidence?.let { e ->
            buildString {
                e.subLabel?.let { append(" sub=").append(it) }
                e.measurement?.let { append(" m=").append(NumFmt.d(digits = 3, v = it)) }
                e.threshold?.let { append(" t=").append(NumFmt.d(digits = 3, v = it)) }
                e.extra?.let { append(" (").append(it).append(')') }
            }
        } ?: ""
        val bar = if (setback != null && f.vl > setback) " §4▲ALERT" else ""
        chat(
            "§8[§dprobe§8] §f${f.checkId} §7${f.label} " +
                "vl=§c${NumFmt.d(digits = 2, v = f.vl)}" +
                (setback?.let { "/§8${NumFmt.d(digits = 2, v = it)}" } ?: "") +
                "$bar §8@t${f.tick}$why"
        )
    }

    /** Clear the printed set (so re-enabling re-prints the deque tail). */
    fun reset() {
        printed.clear()
    }

    private fun chat(msg: String) {
        try {
            net.minecraft.client.MinecraftClient.getInstance().player
                ?.sendMessage(net.minecraft.text.Text.literal(msg), false)
        } catch (_: Throwable) {
        }
    }
}
