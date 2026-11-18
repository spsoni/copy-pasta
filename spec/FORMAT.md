# Copy-Pasta clipboard format, version 2

This is the contract between every Copy-Pasta implementation: the JetBrains plugin (`intellij/`), and the planned VS
Code extension and desktop CLI. Text written by one must paste in all the others. An implementation conforms when it
follows this document **and** passes every case in [`vectors/`](vectors/README.md).

The key words MUST, MUST NOT, SHOULD and MAY are used as in RFC 2119. Sizes: 1 MB = 1,048,576 bytes.

**Character classes.** In every pattern in this document, `\s` means exactly the ASCII whitespace `[\t\n\x0B\f\r ]`
and `\d` means `[0-9]`, never their Unicode versions (JavaScript's `\s` and Rust's `regex` classes are Unicode by
default: spell the class out). Case-insensitive means ASCII letters only. A space inside a marker label is exactly
one U+0020.

The reference implementation is the JetBrains plugin (`intellij/src/main/kotlin/info/suryasoni/copy_pasta/format/`).
Where this document and that code disagree, that's a bug in one of them: fix it and add a vector.

## 1. Overview

```
files and folders ──► ZIP ──► [encrypt: CPv2] ──► Base64 ──► envelope text ──► any chat, email, ticket
```

Pasting reverses it: find the envelope in whatever text the user copied, check it's complete, decode, decrypt if
needed, unpack with strict path and size checks, show a preview, then write.

## 2. Envelope

### 2.1 What writers produce

```
-----BEGIN COPY-PASTA-----
Copy-Pasta: 3 files, 4.1 KB. Paste it with Copy-Pasta for JetBrains IDEs or VS Code: https://github.com/spsoni/copy-pasta
Format: 2; length: 5612
UEsDBBQACAgIA…   (Base64, 76 characters per line)
-----END COPY-PASTA-----
```

Writers MUST produce, with `\n` line endings and a final newline:

1. `-----BEGIN COPY-PASTA-----`, or `-----BEGIN COPY-PASTA ENCRYPTED-----` when the payload is encrypted.
2. The **summary line**: `Copy-Pasta: <summary>. Paste it <anything>`. The summary SHOULD be short and human-readable
   (`3 files, 4.1 KB`). It MUST NOT contain a full stop followed by whitespace (readers stop at the first
   `.\s+Paste it`), CR or LF, a run of three or more `-` (it could form a marker), or `Format:` (readers search for
   the format line anywhere after BEGIN, and this line comes first). The rest of the line tells a recipient who
   doesn't have Copy-Pasta where to get it.
3. `Format: 2; length: <L>`, where `<L>` is the number of Base64 characters in the body.
4. The payload in standard Base64 (RFC 4648 §4: `A–Z a–z 0–9 + /`, `=` padding), wrapped at 76 characters.
5. `-----END COPY-PASTA-----`, or `-----END COPY-PASTA ENCRYPTED-----`.

### 2.2 How readers find and decode it

Chat apps quote, re-wrap, collapse newlines and truncate. Readers MUST tolerate all of that, and MUST report
truncation as such instead of failing with a confusing error.

1. **BEGIN marker.** Search the whole text for the first match of

   ```
   (?<!-)-{3,}+\s*+BEGIN (COPY-PASTA(?: ENCRYPTED)?)\s*-{3,}      (case-insensitive)
   ```

   i.e. three or more dashes that are *not preceded by a dash*, optional whitespace, `BEGIN COPY-PASTA` or
   `BEGIN COPY-PASTA ENCRYPTED`, optional whitespace, three or more dashes. No match: **NOT_FOUND**.

   The search MUST run in linear time. A text of 64k dashes (a log line, a Markdown table) took 17 s with a naive
   backtracking regex. The `(?<!-)` look-behind and the possessive quantifiers prevent that. Equivalents:
   - **JavaScript:** the look-behind alone is enough (a start inside a dash run fails at once, and each run start
     backtracks over its run once): `/(?<!-)-{3,}[\t\n\x0B\f\r ]*BEGIN (COPY-PASTA(?: ENCRYPTED)?)[\t\n\x0B\f\r ]*-{3,}/i`.
     Don't use the `u` or `v` flag, which would make `i` fold non-ASCII letters.
   - **Rust `regex`** (no look-around, linear by construction): drop the look-behind and the possessive quantifiers,
     `(?i-u)-{3,}[\t\n\x0B\f\r ]*BEGIN (COPY-PASTA(?: ENCRYPTED)?)[\t\n\x0B\f\r ]*-{3,}`. It matches the same
     language with the same leftmost match.

   The `dash-flood` vector has a time budget.

   The marker extends to the **end of its trailing dash run** (the final `-{3,}` is greedy). The payload is
   **encrypted** if and only if the matched label contains `ENCRYPTED`. If the text contains several envelopes, only
   the first one is used.

2. **END marker.** Starting at the first character after the BEGIN marker, find the first match of

   ```
   (?<!-)-{3,}+\s*+END COPY-PASTA(?: ENCRYPTED)?\s*-{3,}         (case-insensitive)
   ```

   That starting position counts as the start of the text for the look-behind, so dashes belonging to the BEGIN
   marker can't start the END marker (`begin-end-adjacent` vector). The END label doesn't have to match the BEGIN
   label. The *inner text* is everything between the two markers, or everything after BEGIN if there is no END.

3. **Format line.** Search the inner text (anywhere, not only at a line start, because newlines may have been
   collapsed into spaces) for the first match of `Format:\s*(\d+)\s*;\s*length:\s*(\d+)` (case-insensitive).
   - Found:
     - a version above 2,147,483,647 (signed 32-bit) gives **CORRUPTED**;
     - a version greater than 2 gives **NEWER_VERSION** (the user should update); leading zeros, 0 and 1 are
       accepted;
     - a length above 2,147,483,647 gives **CORRUPTED**.

     The body is the text after the match. Split it into lines (`\n`, `\r\n` or `\r`), remove a leading
     `[\s>]+` from each (quote prefixes and indentation, ASCII whitespace), and join them with all *body
     whitespace* removed.
   - Not found (the line was lost): split the inner text into lines, remove a leading `[\s>]+` from each, remove
     leading and trailing *body whitespace*, keep only the lines that consist entirely of `[A-Za-z0-9+/=]`, and join
     them.

   *Body whitespace* is wider than `\s`, because chat apps insert Unicode spaces: U+0009–000D, U+001C–001F, U+0020,
   U+00A0, U+1680, U+2000–200A, U+2028, U+2029, U+202F, U+205F and U+3000 (Java's `Character.isWhitespace` or
   `isSpaceChar`). Not U+0085, U+180E, U+200B or U+FEFF.

4. **Completeness and exactness**, in this order:
   1. no END marker: **INCOMPLETE**;
   2. any non-ASCII character left in the body: **CORRUPTED** (so lengths never depend on how a language counts
      non-ASCII characters);
   3. a length was declared and the body is shorter: **INCOMPLETE**;
   4. a length was declared and the body is longer: **CORRUPTED**.

   For INCOMPLETE, readers SHOULD tell the user how much arrived (body length) and how much was expected (declared
   length).
5. **Decode** the body as standard Base64 (RFC 4648 §4 alphabet only; never the URL-safe `-` and `_`):
   - padding is optional, but if present, the final 4-character unit must be exactly `xx==` or `xxx=`;
   - a final unit of one character, `=` anywhere else, or anything after the padding gives **CORRUPTED**;
   - non-zero unused bits in the last character are ignored (`QR==` decodes like `QQ==`);
   - an empty body decodes to zero bytes (then INVALID_ARCHIVE, see §4.2).

   Node's `Buffer.from(s, 'base64')` silently skips invalid characters and accepts `-`/`_`: validate first with
   `/^[A-Za-z0-9+\/]*(?:[A-Za-z0-9+\/]{2}==|[A-Za-z0-9+\/]{3}=)?$/` and reject `length % 4 == 1`. Rust's `base64`:
   use a `GeneralPurpose` engine with `DecodePaddingMode::Indifferent` and `with_decode_allow_trailing_bits(true)`.

### 2.3 Peeking (cheap detection)

To show that the clipboard holds Copy-Pasta text without decoding it (menus, status icons), implementations MAY
*peek*:

- Search only the first 65,536 UTF-16 code units for the BEGIN marker.
- Search for the summary in the 400 code units after the marker (clipped to that 65,536 window): the first
  case-sensitive match of `Copy-Pasta:\s*([^\r\n]{1,120}?)\.\s+Paste it`.
- The summary is **untrusted**. Keep only letters and digits (any script in the Basic Multilingual Plane, tested per
  UTF-16 code unit), spaces, `,`, `.` and `-`, trim it, and cap it at 60 code units. If nothing is left, there is no
  summary. Never render it as HTML or markup.

## 3. Payload

### 3.1 Plain

The decoded bytes are a ZIP archive (§4).

### 3.2 Encrypted (`CPv2`)

```
"CPv2" (4 bytes ASCII) | iterations (int32, big-endian) | salt (16 bytes) | iv (12 bytes) | ciphertext ‖ tag (16 bytes)
```

- **Key:** PBKDF2-HMAC-SHA256 over the passphrase's UTF-8 bytes, with the salt and iteration count from the payload,
  32 bytes long. No Unicode normalisation is applied to the passphrase. The passphrase MUST be non-empty, well-formed
  Unicode: writers refuse unpaired UTF-16 surrogates (the JDK would encode one as `?`, other languages differently
  or not at all, so the text could never be decrypted elsewhere).
- **Cipher:** AES-256-GCM, 12-byte IV, 128-bit tag appended to the ciphertext, no additional authenticated data. The
  plaintext is a ZIP archive (§4).
- **Writers** use a fresh random salt and IV from a cryptographically secure generator for every copy, and
  600,000 iterations (OWASP guidance for PBKDF2-HMAC-SHA256). They MAY use more in future versions.
- **Readers** take the iteration count from the payload, never from a constant. They MUST reject, before deriving
  any key, with **INVALID_ARCHIVE**:
  - a payload that doesn't start with `CPv2`;
  - a payload shorter than 52 bytes (no room for the 16-byte GCM tag);
  - an iteration count (signed int32) outside 1–5,000,000, so crafted text can't make the reader spin for minutes.
- A failed GCM tag check gives **WRONG_PASSPHRASE**: a wrong passphrase or tampered text, never garbage output.
- The passphrase is never stored or logged.

## 4. ZIP archive

### 4.1 What writers produce

- A standard ZIP with no encryption. Deflate (level 9 recommended) or Stored. Deflated entries may use data
  descriptors (general-purpose bit 3); Stored entries MUST carry their sizes and CRC-32 in the local header.
- One entry per file. Entry names are relative paths with `/` separators, from the parent of the selected item (or,
  for several selected items, from their closest common ancestor), encoded as UTF-8 (set general-purpose bit 11).
  No directory entries are needed; empty folders aren't transmitted.
- No two entries with the same name.
- The **permissions field** (§4.3) in the extra data of both the local and the central header, with identical
  content. Names must also be identical in both headers.
- Owners, groups and timestamps aren't part of the format. The ZIP's own timestamps are ignored by readers.

The body can be inspected without Copy-Pasta: `base64 -d | unzip -l -`.

### 4.2 How readers unpack it

Readers MUST **stream the local headers** in order, as Java's `ZipInputStream` does, and MUST NOT rely on the
central directory: crafted archives whose local and central headers disagree would otherwise decode differently in
different implementations. Reading stops at the first record that isn't a local file header (`PK\x03\x04`), or
whose 30-byte header is cut short; everything after it, including the central directory, is ignored
(`zip-no-central-directory`, `zip-magic-only` vectors). In Node, use a streaming unzipper (for example fflate's
`Unzip`), not one that starts from the central directory. In Rust, `zip::read::read_zipfile_from_stream`.

1. The payload MUST start with `PK\x03\x04`; otherwise **INVALID_ARCHIVE**.
2. Each entry MUST use method 0 (Stored) or 8 (Deflate) and MUST NOT set the encryption flag (bit 0). A Stored entry
   MUST NOT set bit 3; its length is its uncompressed size (the compressed-size field isn't used, so `0xFFFFFFFF`
   there is not a Zip64 marker). Sizes and the CRC-32 MUST match the inflated content, except that an empty Stored
   entry's CRC isn't checked (the reference stops before comparing it). For bit 3, sizes and CRC come from the data
   descriptor after the data: its `PK\x07\x08` signature is optional, but at least 16 bytes MUST remain after the
   data (the reference reads 16 bytes before deciding), or it's INVALID_ARCHIVE. Truncated data or any other
   structural error: **INVALID_ARCHIVE**. Zip64 is never needed (the limits are far below 4 GB); readers MAY reject
   it as INVALID_ARCHIVE.
3. More than **20,000 local records** in total, directory entries included: **INVALID_ARCHIVE**, so a flood of
   empty entries can't keep the reader busy.
4. Names are decoded as **UTF-8 whatever bit 11 says** (never CP437); invalid UTF-8 is **INVALID_ARCHIVE**. No
   Unicode normalisation (NFC/NFD) is applied.
5. Skip directory entries: names ending in `/`, tested on the raw name before step 6 (`a\` is a file named `a`).
   Their data (normally none) still counts against the size limit of step 9.
6. **Normalise the name:**
   1. reject a name containing NUL or any of U+0001–001F with **INVALID_ARCHIVE**;
   2. replace `\` with `/`;
   3. reject a name that starts with `/`, or with a drive letter and colon (`^[A-Za-z]:`), with **INVALID_ARCHIVE**;
   4. split on `/` and drop empty segments and `.`;
   5. reject an empty result, or any `..` segment, with **INVALID_ARCHIVE**;
   6. join the remaining segments with `/`. This is the file's path from here on.

   Treat every segment as an opaque file name when writing: create files one segment at a time below the target,
   and never hand the path to a path-join that interprets prefixes (Rust's `PathBuf::push("C:")` on Windows replaces
   the whole path).
7. A path equal to an earlier entry's path (exact, case-sensitive comparison): **INVALID_ARCHIVE**.
8. More than 10,000 files: **INVALID_ARCHIVE**.
9. Inflate the content, counting bytes as they are produced, and never trust declared sizes. More than 200 MB in
   total across all entries: **INVALID_ARCHIVE** (zip-bomb protection). Stop inflating as soon as the limit is
   passed.
10. Read the permissions field (§4.3) from the **local header's** extra data. Ignore the external file attributes
   and version-made-by: permissions come only from `0x4350`.

An archive with no files (only directory entries, or nothing) is not an error: tell the user there is nothing to
paste.

### 4.3 Permissions field `0x4350` ("CP")

An extra-data field with header ID `0x4350` (bytes `50 43`) and this data:

| Data byte | Meaning | Since |
|---|---|---|
| 0 | `1` if the file is executable, `0` otherwise | 2.0 |
| 1–2 | the nine permission bits `rwxrwxrwx` (mask `0777`), little-endian | 2.1 |
| 3… | reserved: future versions may append bytes here | — |

**Writers:**

- With POSIX permissions known (macOS, Linux): write 3 data bytes. Byte 0 is 1 if the owner-execute bit is set.
  Bytes 1–2 are the mode masked with `0777`. Setuid, setgid and sticky are never stored.
- When the mode is unknown but the file is known to be executable (for example, a Unix volume without POSIX
  permissions): write 1 data byte, `1`. On Windows the reference writes no field at all.
- Otherwise, write no field.

**Readers:**

- Walk the extra data as `id (uint16 LE) | size (uint16 LE) | data`. Use the first field with ID `0x4350` whose
  declared size is at least 1 and fits within the extra data. Ignore other IDs.
- *executable* = data byte 0 equals `1`.
- *mode* = bytes 1–2 little-endian, masked with `0777`, when the size is at least 3; otherwise the mode is unknown.
- Ignore data bytes beyond the third.
- When the mode is known, it alone decides the permissions applied (§5.3); the executable byte is then only
  informational.

Copy-Pasta 2.0 wrote only byte 0, only for executable files, and reads only byte 0, so 2.0 still gets the executable
flag from newer text.

## 5. Pasting

### 5.1 Plan and preview

Before writing anything, readers MUST show the user every file with its status and let them leave files out:

| Status | Meaning |
|---|---|
| NEW | nothing exists at the path |
| OVERWRITE | a file exists with different content, or the same content but the paste would change its permissions |
| IDENTICAL | a file exists with the same content and permissions (left unticked by default) |
| BLOCKED | never written, whatever the user chooses |

An entry is **BLOCKED** when:

- any path segment names **VCS metadata**, which can execute code (hooks, `fsmonitor` configuration). Compare
  each segment after removing the code points HFS+ ignores (U+200B–200F, U+202A–202E, U+2060–206F, U+FEFF),
  stripping trailing `.` and spaces (Windows drops them), NFC-normalising and *folding*: `.git`, `.hg`, `.svn`,
  `.bzr`, `cvs`, or the Windows 8.3 short names `git~1`, `hg~1`, `svn~1`, `bzr~1`. (A `.git` created while
  another name already shortened to `GIT~1` gets `GIT~2` or a hashed short name; git itself only guards `git~1`,
  and so does this rule.)

  *Folding* means, per code point, upper-case then lower-case (Java's `Character.toLowerCase(toUpperCase(cp))`,
  Unicode simple case mapping), as case-insensitive file systems compare names: U+017F `ſ` → `s`, U+0131 `ı` → `i`,
  U+212A (Kelvin) → `k`. So `.ſvn` is `.svn` on APFS or ext4 casefold;
- **an existing component of its path inside the target, or the file itself, is a symbolic link** (including a
  broken one) **or a special file** (FIFO, socket, device). Writing there would leave the target folder, overwrite
  the file the link points to, or block on a device. Check on disk without following links. On Windows, every
  reparse point that isn't a symlink reports as "other", including ordinary OneDrive placeholders, so there block a
  junction or other reparse point only when its real path is outside the target's real path. Re-check immediately
  before writing each file and refuse (never follow) if a link has appeared since the plan. A name the platform
  can't represent as a path is blocked too;
- on a **Windows target**, a segment contains `:` (a drive or an NTFS alternate data stream), `<`, `>`, `"`, `|`,
  `?` or `*`; ends with `.` or a space; or, folded and with everything from the first `.` removed and trailing spaces
  trimmed, is a reserved device name: `con`, `prn`, `aux`, `nul`, `com0`–`com9`, `lpt0`–`lpt9`, or `com`/`lpt`
  followed by `¹`, `²` or `³` (`con.txt` is the console, `COM¹` a serial port). Other targets accept these names
  (`12:30.log` is a fine Linux file name);
- it duplicates an earlier entry: paths are compared after NFC normalisation (macOS treats NFC and NFD spellings as
  one name), and on a case-insensitive target also after folding;
- its path is also a folder that another entry needs (`p/x` alongside `p/x/y.txt`);
- an existing folder sits at its path, or an existing file sits where it needs a folder.

Symlinks can't be expressed in vectors (they live in the recipient's folder), so every implementation needs its own
test for that rule.

The preview SHOULD label executable files and any other permissions that aren't `rw-r--r--`. It MUST NOT interpret
paths as markup: some UI toolkits render text that starts with `<html>` as HTML.

### 5.2 Writing

- Write only inside the target folder the user chose. The checks in §4.2 and §5.1 are what guarantee that.
- Make the paste undoable as one step where the host supports it.
- Never modify or clear the user's clipboard on your own.

### 5.3 Permissions

Apply permissions on file systems with POSIX permissions only. On Windows, permissions are neither recorded nor
applied. Let *current* be the file's mode right after the content is written: for a new file that's the recipient's
umask default, for an overwritten file its existing mode.

- **Mode known:** set `applied(mode, current)`, where

  ```
  safe(m)             = ((m & 0777) | 0600) & 0755
  applied(m, current) = safe(m) & (current | 0700 | ((current & 0044) >> 2))
  ```

  `safe` keeps the file editable by its owner and never group- or world-writable, and drops special bits.
  `applied` never gives group or others more access than *current* already does, except that read access allows
  execute, so a script runs for whoever could read it. With umask 022, 755 stays 755 and 644 stays 644; with umask
  077, everything stays owner-only.
- **Executable flag only** (text from 2.0): add owner-execute (`0100`) to *current*.
- **Neither:** leave *current* as it is.

Failing to set permissions MUST NOT fail the paste, because the content is already written. Readers SHOULD log the
failure. [`vectors/modes.json`](vectors/modes.json) tabulates `safe` and `applied`.

## 6. Copying

These rules keep the text pasteable everywhere. Hosts implement them with their own facilities.

- Skip files the host's VCS or project settings ignore, unless the user explicitly selected them.
- Use unsaved editor content where the host has it, so the user sends what they see.
- Skip files over 10 MB, and tell the user. Refuse a copy whose total is over 50 MB.
- Skip symbolic links (and Windows junctions) entirely: never follow them and never store them. The format has no
  way to carry a link, so a link can't arrive on the other side either.
- Nested selections (a folder and a file inside it) are copied once. Two selected roots with the same name must not
  merge silently.
- Warn when the text is longer than chat apps usually accept (more than 35,000 UTF-16 code units of envelope text),
  and offer to save it as a file instead.

## 7. Errors

Every failure to decode maps to exactly one category. The vectors use these names.

| Category | Meaning | Suggested message |
|---|---|---|
| NOT_FOUND | no BEGIN marker | "The clipboard doesn't contain Copy-Pasta text." |
| INCOMPLETE | END marker missing, or body shorter than declared | "The text was cut off (received N of M characters)." |
| CORRUPTED | body longer than declared, bad Base64, or unparsable header numbers | "The text is damaged." |
| NEWER_VERSION | `Format` greater than 2 | "This text needs a newer version of Copy-Pasta." |
| WRONG_PASSPHRASE | GCM tag check failed | "Wrong passphrase, or the text was changed." |
| INVALID_ARCHIVE | bad `CPv2` header, bad ZIP, unsafe or duplicate path, over a limit | "The text contains an unsafe or invalid archive." |

## 8. Versioning

- This is `Format: 2`. Readers accept any version up to 2 and reject higher versions with NEWER_VERSION.
- **Compatible changes** keep `Format: 2`: appending bytes to the `0x4350` field, new ZIP extra fields, different
  summary wording, more PBKDF2 iterations (up to the readers' cap). Every 2.x reader must accept anything an earlier
  2.x writer produced.
- **Anything else** (a new payload layout, a new cipher, a different body encoding) needs `Format: 3`, a change to this
  document, new vectors, and a changelog note in every product.
- Copy-Pasta 1.x text (raw Base64 of a tar.gz, no envelope) isn't supported.
