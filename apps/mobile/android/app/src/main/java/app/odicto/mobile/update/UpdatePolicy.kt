package app.odicto.mobile.update

/** When to ask for the newest release, and when to tell the user about it. Pure, so it is tested without a phone. */
object UpdatePolicy {
    const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    const val RETRY_INTERVAL_MS = 60L * 60 * 1000

    /**
     * True when a request should go out now: at most one a day after a success, and at most one an hour after
     * a failure so a bad connection is not retried on every screen change. A clock that moved backwards makes a
     * saved time meaningless, so it counts as due rather than waiting for the clock to catch up.
     */
    fun due(enabled: Boolean, now: Long, checkedAt: Long, attemptedAt: Long): Boolean {
        if (!enabled) return false
        if (checkedAt in 1..now && now - checkedAt < CHECK_INTERVAL_MS) return false
        val failedRecently = attemptedAt > checkedAt && attemptedAt <= now && now - attemptedAt < RETRY_INTERVAL_MS
        return !failedRecently
    }

    /** The version to offer: newer than the one installed, and not one the user already said "Not now" to. */
    fun offer(installed: ReleaseVersion?, latest: ReleaseVersion?, dismissed: ReleaseVersion?): ReleaseVersion? {
        if (installed == null || latest == null) return null
        if (latest <= installed) return null
        if (dismissed != null && latest <= dismissed) return null
        return latest
    }
}
