package info.suryasoni.copy_pasta.ide

import info.suryasoni.copy_pasta.format.Envelope

/** What the clipboard holds, as far as Copy-Pasta cares. Computed by [of]; IDE-free so it can be unit-tested. */
sealed interface ClipboardStatus {
    /** No Copy-Pasta text on the clipboard. */
    data object None : ClipboardStatus

    /** Copy-Pasta text, with the sender's summary line ("3 files, 4.1 KB") when it has one. */
    sealed interface WithText : ClipboardStatus {
        val summary: String?
        val encrypted: Boolean
        val fingerprint: Long
    }

    /** Copy-Pasta text that came from elsewhere and hasn't been pasted yet in this IDE session. */
    data class Ready(override val summary: String?, override val encrypted: Boolean, override val fingerprint: Long) : WithText

    /** Copy-Pasta text that was already pasted from this clipboard content. */
    data class Pasted(override val summary: String?, override val encrypted: Boolean, override val fingerprint: Long) : WithText

    /** Copy-Pasta text that this IDE just copied: it's waiting to be sent, not to be pasted back here. */
    data class Copied(override val summary: String?, override val encrypted: Boolean, override val fingerprint: Long) : WithText

    companion object {
        /**
         * [pastedFingerprint] and [copiedFingerprint] are the [fingerprint]s of the last text pasted and the last
         * text copied in this IDE. The clipboard is never cleared: its state is only ever labelled.
         */
        fun of(text: String?, pastedFingerprint: Long?, copiedFingerprint: Long? = null): ClipboardStatus {
            if (text.isNullOrEmpty()) return None
            val peek = Envelope.peek(text) ?: return None
            val fingerprint = fingerprint(text)
            return when (fingerprint) {
                pastedFingerprint -> Pasted(peek.summary, peek.encrypted, fingerprint)
                copiedFingerprint -> Copied(peek.summary, peek.encrypted, fingerprint)
                else -> Ready(peek.summary, peek.encrypted, fingerprint)
            }
        }

        /** Identifies clipboard content without keeping it: length and hash, enough to tell copies apart. */
        fun fingerprint(text: String): Long = (text.length.toLong() shl 32) or (text.hashCode().toLong() and 0xFFFFFFFFL)
    }
}
