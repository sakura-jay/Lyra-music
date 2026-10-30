package io.github.sakurajay.lyra.core.update

/** 三段版本号。只接受发布 tag 的 `vX.Y.Z`，预发布后缀在这里就不合法。 */
data class ReleaseVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<ReleaseVersion> {

    override fun compareTo(other: ReleaseVersion): Int =
        compareValuesBy(this, other, ReleaseVersion::major, ReleaseVersion::minor, ReleaseVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val TAG = Regex("""^v(\d+)\.(\d+)\.(\d+)$""")

        fun fromTag(tag: String): ReleaseVersion? {
            val match = TAG.matchEntire(tag.trim()) ?: return null
            val (major, minor, patch) = match.destructured
            return ReleaseVersion(major.toIntOrNull() ?: return null, minor.toIntOrNull() ?: return null, patch.toIntOrNull() ?: return null)
        }

        fun fromName(name: String): ReleaseVersion? = fromTag("v${name.trim().removePrefix("v")}")
    }
}
