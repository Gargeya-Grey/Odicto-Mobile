package app.odicto.mobile.update

import org.json.JSONArray

/**
 * Reads GitHub's list of releases for the repository. The list is used rather than the "latest release"
 * endpoint because that endpoint skips releases marked Pre-release, which every Odicto release is so far.
 */
object ReleaseFeed {
    /** The highest published version in the list; drafts and tags that are not a plain version are ignored. */
    fun newest(json: String): ReleaseVersion? {
        val releases = try { JSONArray(json) } catch (_: Exception) { return null }
        var best: ReleaseVersion? = null
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            if (release.optBoolean("draft", false)) continue
            val version = ReleaseVersion.parse(release.optString("tag_name")) ?: continue
            if (best == null || version > best) best = version
        }
        return best
    }
}
