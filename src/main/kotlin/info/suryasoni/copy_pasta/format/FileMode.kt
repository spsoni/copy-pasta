package info.suryasoni.copy_pasta.format

/**
 * Unix permission bits for a copied file: the nine `rwxrwxrwx` bits only. Special bits (setuid, setgid, sticky) are
 * never stored or applied.
 *
 * On paste, [applied] narrows this further to the recipient's umask. [safe] decides what is applied: the sender's bits, but the owner can always read and write the file
 * (so it can be edited, and overwritten by a later paste) and it is never writable by group or others (text from a
 * stranger shouldn't create world-writable files). Examples: 600 stays 600, 755 stays 755, 777 becomes 755,
 * 664 becomes 644, 444 becomes 644.
 */
object FileMode {
    const val PERMISSION_BITS = 0b111_111_111 // 0777
    const val DEFAULT = 0b110_100_100 // 0644
    const val EXECUTABLE = 0b111_101_101 // 0755
    private const val OWNER_READ_WRITE = 0b110_000_000 // 0600
    private const val NO_GROUP_OTHER_WRITE = 0b111_101_101 // 0755
    const val OWNER_EXECUTE = 0b001_000_000 // 0100
    private const val OWNER_ALL = 0b111_000_000 // 0700
    private const val GROUP_OTHER_READ = 0b000_100_100 // 0044

    fun safe(mode: Int): Int = ((mode and PERMISSION_BITS) or OWNER_READ_WRITE) and NO_GROUP_OTHER_WRITE

    /**
     * What a paste applies to a file whose permissions are now [current]: the file the IDE just created (so [current]
     * reflects the recipient's umask) or the existing file being overwritten. The owner bits follow [sender] through
     * [safe]. Group and others can lose access but never gain more than [current] already gives them, except that
     * read access allows execute, so a script still runs for whoever could read it. With umask 022, 755 stays 755
     * and 644 stays 644. With umask 077, everything stays owner-only.
     */
    fun applied(sender: Int, current: Int): Int {
        val now = current and PERMISSION_BITS
        val allowed = now or OWNER_ALL or ((now and GROUP_OTHER_READ) shr 2)
        return safe(sender) and allowed
    }

    fun isOwnerExecutable(mode: Int): Boolean = mode and OWNER_EXECUTE != 0

    /** How the paste preview labels a file's permissions. */
    sealed interface Label {
        data object Executable : Label

        /** Anything other than the usual `rw-r--r--` and `rwxr-xr-x`, e.g. a private key's `rw-------`. */
        data class Bits(val bits: String) : Label
    }

    /** The preview label for what a paste will apply, or null for the usual `rw-r--r--` (or no mode at all). */
    fun label(mode: Int?, executable: Boolean): Label? {
        if (mode == null) return if (executable) Label.Executable else null
        return when (val applied = safe(mode)) {
            DEFAULT -> null
            EXECUTABLE -> Label.Executable
            else -> Label.Bits(describe(applied))
        }
    }

    /** `rwxr-x---` style, as `ls -l` shows it. */
    fun describe(mode: Int): String = buildString {
        for (shift in intArrayOf(6, 3, 0)) {
            val bits = (mode shr shift) and 0b111
            append(if (bits and 0b100 != 0) 'r' else '-')
            append(if (bits and 0b010 != 0) 'w' else '-')
            append(if (bits and 0b001 != 0) 'x' else '-')
        }
    }
}
