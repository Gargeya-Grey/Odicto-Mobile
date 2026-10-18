package app.odicto.mobile.update

import org.junit.Assert.*
import org.junit.Test

class ReleaseFeedTest {
    private fun release(tag: String, draft: Boolean = false, prerelease: Boolean = false) =
        """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"html_url":"https://evil.example/$tag"}"""

    @Test fun picksTheHighestVersionAndCountsPreReleases() {
        val feed = "[" + listOf(release("v1.1.0", prerelease = true), release("v1.2.0", prerelease = true), release("v1.1.1")).joinToString(",") + "]"
        assertEquals(ReleaseVersion(1, 2, 0), ReleaseFeed.newest(feed))
    }

    @Test fun ignoresDraftsAndTagsThatAreNotVersions() {
        val feed = "[" + listOf(release("v9.0.0", draft = true), release("nightly"), release("v1.2.0-rc1"), release("v1.1.1")).joinToString(",") + "]"
        assertEquals(ReleaseVersion(1, 1, 1), ReleaseFeed.newest(feed))
    }

    @Test fun answersNothingForAnythingUnreadable() {
        assertNull(ReleaseFeed.newest("[]"))
        assertNull(ReleaseFeed.newest("""{"message":"Not Found"}"""))
        assertNull(ReleaseFeed.newest("not json"))
        assertNull(ReleaseFeed.newest(""))
        assertNull(ReleaseFeed.newest("""[1, "x", null, {}]"""))
    }
}