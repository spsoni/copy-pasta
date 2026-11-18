package info.suryasoni.copy_pasta.format

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * Cross-implementation check against spec/interop/fixture.json, driven by `.github/workflows/interop.yml` and
 * `make interop`. A no-op in normal test runs. With `-PinteropMode=encode -PinteropDir=<dir>` it writes
 * `intellij-plain.txt` and `intellij-encrypted.txt`; with `-PinteropMode=decode -PinteropDir=<dir> -PinteropFrom=vscode`
 * it decodes `vscode-plain.txt` and `vscode-encrypted.txt` and compares them with the fixture.
 */
class InteropTest {
    private val mode = System.getProperty("copyPasta.interopMode").orEmpty()

    private class Fixture(val passphrase: String, val files: List<FileEntry>)

    private fun fixture(): Fixture {
        val root = JsonParser.parseString(Files.readString(Vectors.dir.resolve("../interop/fixture.json"))).asJsonObject
        val files =
            root.getAsJsonArray("files").map {
                val file = it.asJsonObject
                val mode = file["mode"].takeUnless { m -> m.isJsonNull }?.asString?.toInt(8)
                FileEntry(file["path"].asString, Base64.getDecoder().decode(file["content"].asString), file["executable"].asBoolean, mode)
            }
        return Fixture(root["passphrase"].asString, files)
    }

    private fun describe(files: List<FileEntry>) = files.sortedBy {
        it.path
    }.map { "${it.path} ${Base64.getEncoder().encodeToString(it.bytes)} ${it.mode?.toString(8) ?: "null"} ${it.executable}" }

    @Test
    fun interop() {
        if (mode.isEmpty()) return
        val dir = Path.of(System.getProperty("copyPasta.interopDir"))
        val fixture = fixture()
        when (mode) {
            "encode" -> {
                val zip = ZipCodec.encode(fixture.files)
                val summary = "${fixture.files.size} files"
                Files.writeString(dir.resolve("intellij-plain.txt"), Envelope.encode(zip, encrypted = false, summary))
                val sealed = Crypto.encrypt(zip, fixture.passphrase.toCharArray())
                Files.writeString(dir.resolve("intellij-encrypted.txt"), Envelope.encode(sealed, encrypted = true, summary))
            }
            "decode" -> {
                val from = System.getProperty("copyPasta.interopFrom")
                for (kind in listOf("plain", "encrypted")) {
                    val decoded = Vectors.decodeAll(Files.readString(dir.resolve("$from-$kind.txt")), fixture.passphrase)
                    assertEquals("$from-$kind.txt", describe(fixture.files).joinToString("\n"), describe(decoded).joinToString("\n"))
                }
            }
            else -> error("unknown interop mode $mode")
        }
    }
}
