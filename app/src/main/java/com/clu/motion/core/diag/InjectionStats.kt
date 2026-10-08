package com.clu.motion.core.diag

import kotlin.math.abs

/** Order statistics of a sample window, in milliseconds. */
data class Percentiles(val count: Int, val p50: Long, val p95: Long, val max: Long) {
    override fun toString() = "n=$count p50=${p50}ms p95=${p95}ms max=${max}ms"
}

/** What the injector sent and what the on-screen test pad actually received. */
data class InjectionSnapshot(
    val dispatched: Int,
    val completed: Int,
    val cancelled: Int,
    val rejected: Int,
    val backpressureSkips: Int,
    /** Age of the motion frame used for a dispatch (sensor frame → dispatchGesture). */
    val frameAge: Percentiles?,
    /** dispatchGesture → the pad receiving a pointer at that segment's end point. */
    val dispatchToDelivery: Percentiles?,
    /** MotionEvent.eventTime → the pad's onTouchEvent (input pipeline + main thread). */
    val eventAge: Percentiles?,
    val downs: Int,
    val ups: Int,
    val cancels: Int,
    val pointerDowns: Int,
    val pointerUps: Int,
    val moves: Int,
    /** Gaps between consecutive MOVE events while a finger is down: stalls show up here. */
    val moveGap: Percentiles?,
    val maxPointers: Int,
    /** "source/tool/flags" signatures of received events, with counts. */
    val signatures: Map<String, Int>,
)

/**
 * In-process recorder for the on-device injection self-test.
 *
 * Disabled by default so normal play pays nothing; the test screen turns it on. Thread-safe:
 * dispatch callbacks arrive on the injection thread, touch events on the main thread.
 */
class InjectionStats(private val window: Int = 600) {

    @Volatile
    var enabled = false

    private val lock = Any()
    private var dispatched = 0
    private var completed = 0
    private var cancelled = 0
    private var rejected = 0
    private var backpressureSkips = 0
    private val frameAges = Samples(window)
    private val deliveries = Samples(window)
    private val eventAges = Samples(window)
    private val moveGaps = Samples(window)
    private val targets = ArrayDeque<Target>()
    private val actions = IntArray(8)
    private var lastMoveTime = -1L
    private var maxPointers = 0
    private val signatures = LinkedHashMap<String, Int>()

    private class Target(val key: Int, val x: Int, val y: Int, val dispatchMs: Long)

    fun reset() = synchronized(lock) {
        dispatched = 0
        completed = 0
        cancelled = 0
        rejected = 0
        backpressureSkips = 0
        frameAges.clear()
        deliveries.clear()
        eventAges.clear()
        moveGaps.clear()
        targets.clear()
        actions.fill(0)
        lastMoveTime = -1L
        maxPointers = 0
        signatures.clear()
    }

    /** [endpoints]: (pointer key, end x, end y) of every segment in the dispatched gesture. */
    fun onDispatch(nowMs: Long, frameAgeMs: Long?, endpoints: List<Triple<Int, Int, Int>>) {
        if (!enabled) return
        synchronized(lock) {
            dispatched++
            frameAgeMs?.let { frameAges.add(it) }
            for ((key, x, y) in endpoints) targets.addLast(Target(key, x, y, nowMs))
            while (targets.isNotEmpty() && (targets.size > MAX_TARGETS || nowMs - targets.first().dispatchMs > TARGET_TTL_MS)) {
                targets.removeFirst()
            }
        }
    }

    fun onCompleted() = count { completed++ }
    fun onCancelled() = count { cancelled++ }
    fun onRejected() = count { rejected++ }
    fun onBackpressure() = count { backpressureSkips++ }

    /**
     * One MotionEvent seen by the test pad. Coordinates are absolute display pixels;
     * [actionMasked] uses android.view.MotionEvent action values.
     */
    fun onTouch(
        actionMasked: Int,
        eventTimeMs: Long,
        nowMs: Long,
        xs: FloatArray,
        ys: FloatArray,
        pointerCount: Int,
        signature: String,
    ) {
        if (!enabled) return
        synchronized(lock) {
            if (actionMasked in actions.indices) actions[actionMasked]++
            if (pointerCount > maxPointers) maxPointers = pointerCount
            eventAges.add(nowMs - eventTimeMs)
            signatures[signature] = (signatures[signature] ?: 0) + 1
            when (actionMasked) {
                ACTION_DOWN -> lastMoveTime = eventTimeMs
                ACTION_MOVE -> {
                    if (lastMoveTime >= 0) moveGaps.add(eventTimeMs - lastMoveTime)
                    lastMoveTime = eventTimeMs
                }
                ACTION_UP, ACTION_CANCEL -> lastMoveTime = -1L
            }
            for (i in 0 until pointerCount) matchTarget(xs[i], ys[i], nowMs)
        }
    }

    fun snapshot(): InjectionSnapshot = synchronized(lock) {
        InjectionSnapshot(
            dispatched = dispatched,
            completed = completed,
            cancelled = cancelled,
            rejected = rejected,
            backpressureSkips = backpressureSkips,
            frameAge = frameAges.percentiles(),
            dispatchToDelivery = deliveries.percentiles(),
            eventAge = eventAges.percentiles(),
            downs = actions[ACTION_DOWN],
            ups = actions[ACTION_UP],
            cancels = actions[ACTION_CANCEL],
            pointerDowns = actions[ACTION_POINTER_DOWN],
            pointerUps = actions[ACTION_POINTER_UP],
            moves = actions[ACTION_MOVE],
            moveGap = moveGaps.percentiles(),
            maxPointers = maxPointers,
            signatures = LinkedHashMap(signatures),
        )
    }

    /** Matches a received pointer to the newest pending segment end point at that pixel. */
    private fun matchTarget(x: Float, y: Float, nowMs: Long) {
        val it = targets.listIterator(targets.size)
        while (it.hasPrevious()) {
            val t = it.previous()
            if (abs(t.x - x) <= MATCH_PX && abs(t.y - y) <= MATCH_PX) {
                deliveries.add(nowMs - t.dispatchMs)
                // Older targets of the same pointer were superseded; drop them with this one.
                targets.removeAll { it.key == t.key && it.dispatchMs <= t.dispatchMs }
                return
            }
        }
    }

    private inline fun count(block: () -> Unit) {
        if (!enabled) return
        synchronized(lock) { block() }
    }

    private class Samples(private val capacity: Int) {
        private val values = LongArray(capacity)
        private var size = 0
        private var next = 0

        fun add(v: Long) {
            values[next] = v
            next = (next + 1) % capacity
            if (size < capacity) size++
        }

        fun clear() {
            size = 0
            next = 0
        }

        fun percentiles(): Percentiles? {
            if (size == 0) return null
            val sorted = values.copyOf(size).also { it.sort() }
            fun at(q: Double) = sorted[((size - 1) * q).toInt()]
            return Percentiles(size, at(0.5), at(0.95), sorted[size - 1])
        }
    }

    companion object {
        // android.view.MotionEvent action values (kept literal: this class is Android-free).
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3
        const val ACTION_POINTER_DOWN = 5
        const val ACTION_POINTER_UP = 6

        private const val MATCH_PX = 1f
        private const val MAX_TARGETS = 256
        private const val TARGET_TTL_MS = 1_000L
    }
}
