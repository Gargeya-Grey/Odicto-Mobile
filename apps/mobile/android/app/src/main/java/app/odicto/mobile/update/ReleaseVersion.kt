package app.odicto.mobile.update

/** A MAJOR.MINOR.PATCH release number, the only shape Odicto publishes. Anything else is not a release. */
data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val SHAPE = Regex("""^v?(\d{1,4})\.(\d{1,4})\.(\d{1,4})$""")

        /** Accepts `1.2.3` or `v1.2.3`. Pre-release suffixes, extra parts and blanks are refused. */
        fun parse(text: String?): ReleaseVersion? {
            val match = SHAPE.matchEntire(text?.trim().orEmpty()) ?: return null
            val (major, minor, patch) = match.destructured
            return ReleaseVersion(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}
