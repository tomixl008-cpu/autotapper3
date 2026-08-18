package com.example.autotexttapper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * User-controlled, text-driven tap assistant.
 *
 * The device owner must manually enable this service in Android Settings >
 * Accessibility. Nothing runs until the user presses Start in the UI, and Stop
 * cancels everything immediately.
 *
 * Safety scope: this service only READS visible accessible text/content
 * descriptions and performs taps/swipes the user asked for. It does NOT use
 * screenshot capture, OCR, root, overlays, CAPTCHA handling, ad-click or
 * financial/login automation, protected-screen bypasses or anti-detection.
 *
 * Behaviour (finite state machine, see [State]):
 *
 *   Start -> IDLE -> INITIAL_WAIT (5 s) -> FIND_LIKE_OR_SKIP
 *
 *   FIND_LIKE_OR_SKIP ("Like video" ALWAYS wins over "Skip"):
 *     - "Like video" found  -> click -> WAIT_AFTER_LIKE (5 s)
 *       -> DOUBLE_TAP_CENTER -> WAIT_AFTER_DOUBLE_TAP (2 s)
 *       -> FIRST_SWIPE_BACK -> WAIT_BETWEEN_SWIPES (0.5 s)
 *       -> SECOND_SWIPE_BACK -> WAIT_FOR_LOADING (-> WAIT_FOR_LOADING_TO_FINISH)
 *       -> WAIT_AFTER_LOADING_FINISH (0.5 s settle) -> FIND_LIKE_OR_SKIP
 *     - "Skip" found         -> click -> WAIT_AFTER_SKIP (4 s) -> FIND_LIKE_OR_SKIP
 *     - nothing found        -> do not touch the screen; rescan on window events
 *                               or after MAIN_SCAN_INTERVAL_MS (1 s) fallback.
 */
class TextAutomationAccessibilityService : AccessibilityService() {

    // ------------------------------------------------------------------
    // State machine
    // ------------------------------------------------------------------

    enum class State {
        IDLE,
        INITIAL_WAIT,
        FIND_LIKE_OR_SKIP,
        WAIT_AFTER_LIKE,
        DOUBLE_TAP_CENTER,
        WAIT_AFTER_DOUBLE_TAP,
        FIRST_SWIPE_BACK,
        WAIT_BETWEEN_SWIPES,
        SECOND_SWIPE_BACK,
        WAIT_FOR_LOADING,
        WAIT_FOR_LOADING_TO_FINISH,
        WAIT_AFTER_LOADING_FINISH,
        WAIT_AFTER_SKIP
    }

    /** Current FSM state; only touched on the main thread. */
    private var state: State = State.IDLE

    /**
     * Monotonic token incremented on every Start/Stop/destroy. Every scheduled
     * callback and gesture callback captures the token at creation time and
     * refuses to act if the token changed, so stale work can never fire after
     * a Stop or after the service was re-bound.
     */
    private var runToken = 0

    /** True while a text-node click attempt is being resolved asynchronously. */
    private var clickInFlight = false

    /** Timestamp (elapsedRealtime) when the Loading wait route began. */
    private var loadingRouteStartMs = 0L

    /** Timestamp (elapsedRealtime) when "Loading" was first detected. */
    private var loadingSeenStartMs = 0L

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Fallback scan loop for FIND_LIKE_OR_SKIP. Single Runnable instance plus
     * removeCallbacks-before-post guarantees only one queued scan at a time.
     */
    private val mainScanRunnable = object : Runnable {
        override fun run() {
            if (state != State.FIND_LIKE_OR_SKIP) return
            performMainScan()
            if (state == State.FIND_LIKE_OR_SKIP) {
                handler.postDelayed(this, MAIN_SCAN_INTERVAL_MS)
            }
        }
    }

    /** 1-second fallback check loop for the Loading wait route. */
    private val loadingCheckRunnable = object : Runnable {
        override fun run() {
            if (state != State.WAIT_FOR_LOADING &&
                state != State.WAIT_FOR_LOADING_TO_FINISH
            ) {
                return
            }
            performLoadingCheck()
            if (state == State.WAIT_FOR_LOADING ||
                state == State.WAIT_FOR_LOADING_TO_FINISH
            ) {
                handler.postDelayed(this, MAIN_SCAN_INTERVAL_MS)
            }
        }
    }

    // ------------------------------------------------------------------
    // AccessibilityService lifecycle
    // ------------------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        runToken++
        state = State.IDLE
        StatusHolder.update(this, getString(R.string.status_enabled_ready))
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onInterrupt() {
        // The system interrupted our feedback: stop safely.
        if (state != State.IDLE) {
            stopAutomation()
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        runToken++
        clickInFlight = false
        state = State.IDLE
        if (instance === this) instance = null
        StatusHolder.update(this, getString(R.string.status_service_disabled))
        Log.i(TAG, "Accessibility service shut down")
    }

    // ------------------------------------------------------------------
    // Public control API (used by MainActivity)
    // ------------------------------------------------------------------

    /** User pressed Start: cancel leftovers, then wait 5 s before scanning. */
    fun startAutomation() {
        handler.removeCallbacksAndMessages(null)
        runToken++
        clickInFlight = false
        setState(State.INITIAL_WAIT, getString(R.string.status_started_waiting))
        runAfter(INITIAL_DELAY_MS) {
            goToMainScan(0L)
        }
    }

    /** User pressed Stop: cancel everything and go IDLE. */
    fun stopAutomation() {
        handler.removeCallbacksAndMessages(null)
        runToken++
        clickInFlight = false
        setState(State.IDLE, getString(R.string.status_stopped))
        Log.i(TAG, "Automation stopped by user")
    }

    // ------------------------------------------------------------------
    // Accessibility events
    // ------------------------------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        when (state) {
            State.FIND_LIKE_OR_SKIP -> requestMainScan()
            State.WAIT_FOR_LOADING, State.WAIT_FOR_LOADING_TO_FINISH ->
                requestLoadingCheck()
            else -> Unit
        }
    }

    private fun requestMainScan() {
        // Debounce: replace any queued fallback or event-driven scan.
        handler.removeCallbacks(mainScanRunnable)
        handler.post(mainScanRunnable)
    }

    private fun requestLoadingCheck() {
        handler.removeCallbacks(loadingCheckRunnable)
        handler.post(loadingCheckRunnable)
    }

    // ------------------------------------------------------------------
    // State helpers
    // ------------------------------------------------------------------

    private fun setState(newState: State, status: String) {
        state = newState
        StatusHolder.update(this, status)
        Log.d(TAG, "state=$newState status=\"$status\"")
    }

    /** Posts [action] after [delayMs]; it runs only if no Stop/Start happened since. */
    private fun runAfter(delayMs: Long, action: () -> Unit) {
        val token = runToken
        handler.postDelayed({
            if (token != runToken) return@postDelayed
            action()
        }, delayMs)
    }

    private fun goToMainScan(delayMs: Long, status: String? = null) {
        handler.removeCallbacks(mainScanRunnable)
        setState(
            State.FIND_LIKE_OR_SKIP,
            status ?: getString(R.string.status_searching)
        )
        if (delayMs <= 0L) {
            handler.post(mainScanRunnable)
        } else {
            handler.postDelayed(mainScanRunnable, delayMs)
        }
    }

    // ------------------------------------------------------------------
    // B. MAIN SCAN — "Like video" always has priority over "Skip"
    // ------------------------------------------------------------------

    private fun performMainScan() {
        if (state != State.FIND_LIKE_OR_SKIP) return
        if (clickInFlight) return // a click attempt is resolving; never double-fire

        val root = rootInActiveWindow ?: return // nothing readable: wait quietly
        try {
            // PRIORITY 1 — "Like video". If present together with "Skip",
            // "Skip" is NEVER clicked.
            val like = findNodeByText(root, LIKE_VIDEO_TEXT)
            if (like != null) {
                handleLikeVideo(like)
                return
            }

            // PRIORITY 2 — "Skip" (only because no "Like video" is visible).
            val skip = findNodeByText(root, SKIP_TEXT)
            if (skip != null) {
                handleSkip(skip)
                return
            }

            // Neither text is visible: do not touch the screen. The fallback
            // scan runs 1 s later; window events wake us earlier.
        } finally {
            recycleSafely(root)
        }
    }

    // ------------------------------------------------------------------
    // C. LIKE VIDEO ROUTE
    // ------------------------------------------------------------------

    private fun handleLikeVideo(node: AccessibilityNodeInfo) {
        clickInFlight = true
        val token = runToken
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        clickNode(node, bounds) { success ->
            recycleSafely(node)
            clickInFlight = false
            if (token != runToken || state != State.FIND_LIKE_OR_SKIP) return@clickNode
            if (!success) return@clickNode // stay scanning; fallback rescans in 1 s

            setState(State.WAIT_AFTER_LIKE, getString(R.string.status_like_clicked))
            runAfter(WAIT_AFTER_LIKE_MS) { startDoubleTapCenter() }
        }
    }

    private fun startDoubleTapCenter() {
        if (state != State.WAIT_AFTER_LIKE) return
        setState(State.DOUBLE_TAP_CENTER, getString(R.string.status_double_tapping))
        val token = runToken
        doubleTapCentre { success ->
            if (token != runToken || state != State.DOUBLE_TAP_CENTER) return@doubleTapCentre
            if (!success) {
                abortToMainScan()
                return@doubleTapCentre
            }
            startWaitAfterDoubleTap()
        }
    }

    private fun startWaitAfterDoubleTap() {
        setState(State.WAIT_AFTER_DOUBLE_TAP, getString(R.string.status_double_tapping))
        runAfter(DOUBLE_TAP_TO_BACK_DELAY_MS) { startFirstSwipeBack() }
    }

    private fun startFirstSwipeBack() {
        if (state != State.WAIT_AFTER_DOUBLE_TAP) return
        setState(State.FIRST_SWIPE_BACK, getString(R.string.status_first_swipe))
        val token = runToken
        performBack { success ->
            if (token != runToken || state != State.FIRST_SWIPE_BACK) return@performBack
            if (!success) {
                abortToMainScan()
                return@performBack
            }
            startWaitBetweenSwipes()
        }
    }

    private fun startWaitBetweenSwipes() {
        setState(State.WAIT_BETWEEN_SWIPES, getString(R.string.status_waiting_between_swipes))
        runAfter(WAIT_BETWEEN_SWIPES_MS) { startSecondSwipeBack() }
    }

    private fun startSecondSwipeBack() {
        if (state != State.WAIT_BETWEEN_SWIPES) return
        setState(State.SECOND_SWIPE_BACK, getString(R.string.status_second_swipe))
        val token = runToken
        performBack { success ->
            if (token != runToken || state != State.SECOND_SWIPE_BACK) return@performBack
            if (!success) {
                abortToMainScan()
                return@performBack
            }
            startLoadingWait()
        }
    }

    // ------------------------------------------------------------------
    // D. LOADING WAIT ROUTE
    // ------------------------------------------------------------------

    private fun startLoadingWait() {
        loadingRouteStartMs = SystemClock.elapsedRealtime()
        handler.removeCallbacks(loadingCheckRunnable)
        setState(State.WAIT_FOR_LOADING, getString(R.string.status_waiting_for_loading))
        handler.post(loadingCheckRunnable)
    }

    private fun performLoadingCheck() {
        when (state) {
            State.WAIT_FOR_LOADING -> {
                if (isLoadingVisible()) {
                    loadingSeenStartMs = SystemClock.elapsedRealtime()
                    setState(
                        State.WAIT_FOR_LOADING_TO_FINISH,
                        getString(R.string.status_loading_detected)
                    )
                } else if (
                    SystemClock.elapsedRealtime() - loadingRouteStartMs >= LOADING_TIMEOUT_MS
                ) {
                    // "Loading" never appeared: do not wait forever.
                    goToMainScan(0L, getString(R.string.status_loading_timeout))
                }
            }

            State.WAIT_FOR_LOADING_TO_FINISH -> {
                if (!isLoadingVisible()) {
                    finishLoading()
                } else if (
                    SystemClock.elapsedRealtime() - loadingSeenStartMs >=
                    LOADING_FINISH_TIMEOUT_MS
                ) {
                    // Safety net only: never stay stuck if the UI wedges.
                    finishLoading()
                }
            }

            else -> Unit
        }
    }

    private fun isLoadingVisible(): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            val loading = findNodeByText(root, LOADING_TEXT) ?: return false
            recycleSafely(loading)
            return true
        } finally {
            recycleSafely(root)
        }
    }

    private fun finishLoading() {
        handler.removeCallbacks(loadingCheckRunnable)
        setState(
            State.WAIT_AFTER_LOADING_FINISH,
            getString(R.string.status_loading_finished)
        )
        // Extra settle delay; the UI may still be updating, then rescan.
        runAfter(LOADING_SETTLE_DELAY_MS) { goToMainScan(0L) }
    }

    // ------------------------------------------------------------------
    // E. SKIP ROUTE
    // ------------------------------------------------------------------

    private fun handleSkip(node: AccessibilityNodeInfo) {
        clickInFlight = true
        val token = runToken
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        clickNode(node, bounds) { success ->
            recycleSafely(node)
            clickInFlight = false
            if (token != runToken || state != State.FIND_LIKE_OR_SKIP) return@clickNode
            if (!success) return@clickNode // stay scanning; fallback rescans in 1 s

            // No double-tap, no swipe, no Loading wait on the Skip route.
            setState(State.WAIT_AFTER_SKIP, getString(R.string.status_skip_clicked))
            runAfter(WAIT_AFTER_SKIP_MS) { goToMainScan(0L) }
        }
    }

    // ------------------------------------------------------------------
    // F. Failure path — a cancelled gesture must not continue the route blindly
    // ------------------------------------------------------------------

    private fun abortToMainScan() {
        goToMainScan(
            MAIN_SCAN_INTERVAL_MS,
            getString(R.string.status_gesture_failed)
        )
    }

    // ------------------------------------------------------------------
    // Node search + click helpers
    // ------------------------------------------------------------------

    /**
     * Depth-first search over the node tree for a node whose text OR content
     * description equals [target] exactly (case-insensitive, whitespace trimmed).
     *
     * Returns an owned copy (must be passed to [recycleSafely]) or null.
     * [root] itself is never recycled here — the caller owns it.
     */
    private fun findNodeByText(
        root: AccessibilityNodeInfo,
        target: String
    ): AccessibilityNodeInfo? {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (matchesText(node, target)) {
                val copy = AccessibilityNodeInfo.obtain(node)
                if (node !== root) recycleSafely(node)
                while (pending.isNotEmpty()) recycleSafely(pending.removeFirst())
                return copy
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { pending.addLast(it) }
            }
            if (node !== root) recycleSafely(node)
        }
        return null
    }

    private fun matchesText(node: AccessibilityNodeInfo, target: String): Boolean {
        val text = node.text?.toString()?.trim()
        val description = node.contentDescription?.toString()?.trim()
        return (!text.isNullOrEmpty() && text.equals(target, ignoreCase = true)) ||
            (!description.isNullOrEmpty() && description.equals(target, ignoreCase = true))
    }

    /**
     * Clicks [node] using, in order:
     *  1. performAction(ACTION_CLICK) on the node itself;
     *  2. performAction(ACTION_CLICK) on the first clickable ancestor;
     *  3. a single-tap gesture at the centre of the node's screen bounds.
     *
     * The result is delivered asynchronously through [onResult].
     */
    private fun clickNode(
        node: AccessibilityNodeInfo,
        bounds: Rect,
        onResult: (Boolean) -> Unit
    ) {
        // 1. Direct click on the matched node.
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            onResult(true)
            return
        }

        // 2. Walk up the tree to the first clickable parent.
        var current: AccessibilityNodeInfo? = node.parent
        while (current != null) {
            val clicked =
                current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val next: AccessibilityNodeInfo? = if (clicked) null else {
                val parent = current.parent
                parent
            }
            recycleSafely(current)
            if (clicked) {
                onResult(true)
                return
            }
            current = next
        }

        // 3. Gesture fallback at the centre of the node's bounds.
        if (bounds.isEmpty) {
            onResult(false)
        } else {
            tapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat(), onResult)
        }
    }

    // ------------------------------------------------------------------
    // Gesture helpers (all dispatch through GestureResultCallback chains)
    // ------------------------------------------------------------------

    /** Single tap of duration [TAP_DURATION_MS] at ([x], [y]). */
    private fun tapAt(x: Float, y: Float, onResult: (Boolean) -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS)
        dispatchStroke(stroke, onResult)
    }

    /**
     * Two short taps at the exact centre of the active display, with
     * [TAP_DURATION_MS] per tap and [DOUBLE_TAP_GAP_MS] between the end of the
     * first tap and the start of the second.
     */
    private fun doubleTapCentre(onResult: (Boolean) -> Unit) {
        val (width, height) = screenSize()
        val centreX = width / 2f
        val centreY = height / 2f

        tapAt(centreX, centreY) { firstOk ->
            if (!firstOk) {
                onResult(false)
                return@tapAt
            }
            val token = runToken
            handler.postDelayed({
                if (token != runToken) return@postDelayed
                tapAt(centreX, centreY, onResult)
            }, DOUBLE_TAP_GAP_MS)
        }
    }

    /**
     * Performs one back action with the platform global action first. If the
     * global action cannot be performed, falls back to an edge back gesture.
     */
    private fun performBack(onResult: (Boolean) -> Unit) {
        val globalActionSucceeded = try {
            performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (t: Throwable) {
            Log.w(TAG, "Global back action failed", t)
            false
        }
        if (globalActionSucceeded) {
            onResult(true)
        } else {
            edgeBackGesture(onResult)
        }
    }

    /**
     * Left-to-right edge back gesture, starting exactly at the left screen
     * edge, from SWIPE_START_X_RATIO to SWIPE_END_X_RATIO of the screen width
     * at SWIPE_Y_RATIO of the screen height.
     */
    private fun edgeBackGesture(onResult: (Boolean) -> Unit) {
        val (width, height) = screenSize()
        val path = Path().apply {
            moveTo(width * SWIPE_START_X_RATIO, height * SWIPE_Y_RATIO)
            lineTo(width * SWIPE_END_X_RATIO, height * SWIPE_Y_RATIO)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, SWIPE_DURATION_MS)
        dispatchStroke(stroke, onResult)
    }

    /**
     * Dispatches one stroke and reports completion/cancellation.
     * onResult(false) is also delivered if the system refuses to dispatch.
     */
    private fun dispatchStroke(
        stroke: GestureDescription.StrokeDescription,
        onResult: (Boolean) -> Unit
    ) {
        val description = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = try {
            dispatchGesture(
                description,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        handler.post { onResult(true) }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        handler.post { onResult(false) }
                    }
                },
                handler
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Gesture dispatch failed", t)
            false
        }
        if (!dispatched) {
            onResult(false)
        }
    }

    /** Best-effort size of the active display in pixels. */
    private fun screenSize(): Pair<Float, Float> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val wm = getSystemService(WindowManager::class.java)
                val bounds = wm?.currentWindowMetrics?.bounds
                if (bounds != null) {
                    bounds.width().toFloat() to bounds.height().toFloat()
                } else {
                    resourceScreenSize()
                }
            } else {
                resourceScreenSize()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to read screen size", t)
            resourceScreenSize()
        }
    }

    private fun resourceScreenSize(): Pair<Float, Float> {
        val metrics = resources.displayMetrics
        return metrics.widthPixels.toFloat() to metrics.heightPixels.toFloat()
    }

    /**
     * recycle() is needed on API < 33 to avoid node leaks; on newer APIs the
     * runtime manages nodes and the call is a deprecated no-op.
     */
    private fun recycleSafely(node: AccessibilityNodeInfo?) {
        if (node == null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            try {
                node.recycle()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to recycle node", t)
            }
        }
    }

    // ------------------------------------------------------------------
    // Constants — edit these to change texts, delays and gesture geometry
    // ------------------------------------------------------------------

    companion object {
        private const val TAG = "TextAutomationSvc"

        // Target texts (exact match, case-insensitive, trimmed).
        const val LIKE_VIDEO_TEXT = "Like video"
        const val SKIP_TEXT = "Skip"
        const val LOADING_TEXT = "Loading"

        // Delays / intervals.
        const val INITIAL_DELAY_MS = 5000L
        const val WAIT_AFTER_LIKE_MS = 5000L
        const val DOUBLE_TAP_TO_BACK_DELAY_MS = 2000L
        const val WAIT_BETWEEN_SWIPES_MS = 500L
        const val WAIT_AFTER_SKIP_MS = 4000L
        const val MAIN_SCAN_INTERVAL_MS = 1000L
        const val LOADING_SETTLE_DELAY_MS = 500L
        const val LOADING_TIMEOUT_MS = 30000L

        /** Safety net only: max time to wait for a visible "Loading" to disappear. */
        const val LOADING_FINISH_TIMEOUT_MS = 120000L

        // Gesture geometry.
        const val TAP_DURATION_MS = 60L
        const val DOUBLE_TAP_GAP_MS = 150L
        const val SWIPE_START_X_RATIO = 0.0f
        const val SWIPE_END_X_RATIO = 0.82f
        const val SWIPE_Y_RATIO = 0.50f
        const val SWIPE_DURATION_MS = 300L

        /**
         * Live instance while the service is bound; MainActivity uses it to
         * forward Start/Stop. Null when the user has not enabled the service.
         */
        @Volatile
        var instance: TextAutomationAccessibilityService? = null
            private set
    }
}
