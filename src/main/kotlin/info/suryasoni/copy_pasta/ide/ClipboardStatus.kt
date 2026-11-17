package info.suryasoni.copy_pasta.ide

import info.suryasoni.copy_pasta.format.Envelope

/** What the clipboard holds, as far as Copy-Pasta cares. Computed by [of]; IDE-free so it can be unit-tested. */
sealed interface ClipboardStatus {
    /** No Copy-Pasta text on the clipboard. */
    data object None : ClipboardStatus

    /** Copy-Pasta text that hasn't been pasted yet in this IDE session. */
    data class Ready(val summary: String?, val encrypted: Boolean, val fingerprint: Long) : ClipboardStatus

    /** Copy-Pasta text that was already pasted from this clipboard content. */
    data class Pasted(val summary: String?, val encrypted: Boolean, val fingerprint: Long) : ClipboardStatus

    companion object {
        /**
         * [pastedFingerprint] is the [fingerprint] of the last text that was pasted successfully. The clipboard is
         * never cleared: once its text has been pasted it simply shows as [Pasted].
         */
        fun of(text: String?, pastedFingerprint: Long?): ClipboardStatus {
            if (text.isNullOrEmpty()) return None
            val peek = Envelope.peek(text) ?: return None
            val fingerprint = fingerprint(text)
            return if (fingerprint == pastedFingerprint) {
                Pasted(peek.summary, peek.encrypted, fingerprint)
            } else {
                Ready(peek.summary, peek.encrypted, fingerprint)
            }
        }

        /** Identifies clipboard content without keeping it: length and hash, enough to tell copies apart. */
        fun fingerprint(text: String): Long = (text.length.toLong() shl 32) or (text.hashCode().toLong() and 0xFFFFFFFFL)
    }
}
