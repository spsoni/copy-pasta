# Conformance vectors

Golden inputs that every Copy-Pasta implementation must decode exactly as expected. They freeze
[`../FORMAT.md`](../FORMAT.md). If a vector and the spec disagree, fix one of them in the same change.

## Files

- `<case>.txt`: the text a user would paste, byte for byte (UTF-8). `.gitattributes` keeps git from changing its
  line endings: the `crlf` case is CRLF on purpose.
- [`manifest.json`](manifest.json): one entry per case:

  ```json
  {
    "name": "plain-project",
    "description": "…",
    "file": "plain-project.txt",
    "passphrase": null,              // use it when the envelope is encrypted
    "maxMillis": 2000,               // optional time budget for the whole decode
    "expect": {
      "error": "INCOMPLETE"          // either an error category (FORMAT.md §7) …
      "files": [                     // … or the decoded files, in any order
        { "path": "demo/run.sh", "content": "<Base64 of the bytes>", "mode": "0755", "executable": true }
      ],
      "encrypted": true,             // optional: the label says ENCRYPTED
      "blocked": ["p/.git/hooks/x"], // optional: paths a paste plan must block (case-insensitive target)
      "blockedOnWindows": ["con.txt"], // optional: blocked in addition on a Windows target
      "summary": "7 files, 1 KB"     // optional: what peeking reports (FORMAT.md §2.3)
    }
  }
  ```

  `mode` is an octal string, or `null` when the text doesn't carry one.
- [`modes.json`](modes.json): `sender` and `current` modes with the expected `safe` and `applied` results
  (FORMAT.md §5.3).

## Running them

Each implementation runs every case through its full decode path (envelope → decrypt if encrypted → ZIP) in its own
CI, and compares files (path, bytes, mode, executable flag) or the error category.

- JetBrains plugin: `intellij/src/test/kotlin/info/suryasoni/copy_pasta/format/ConformanceTest.kt` and
  `.../ide/ConformancePlanTest.kt`, part of `make check`.

## Adding a case

Add it to `intellij/src/test/kotlin/info/suryasoni/copy_pasta/format/VectorGenerator.kt` with a hand-written
expectation, then run `make vectors`. Existing `.txt` files are kept: encrypted cases have a random salt and IV, and
ZIPs carry timestamps, so regenerating them would churn the diff. `make vectors FORCE=1` rewrites everything.
