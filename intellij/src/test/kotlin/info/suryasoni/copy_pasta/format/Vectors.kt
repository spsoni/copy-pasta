package info.suryasoni.copy_pasta.format

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * The shared conformance vectors in `spec/vectors/` (see `spec/FORMAT.md`). Every Copy-Pasta implementation decodes
 * every case and must get the expected files or the expected error category. Gson comes with the IDE platform and
 * is used by tests only, so the plugin stays free of third-party libraries.
 */
object Vectors {
    /** Set by build.gradle.kts; the fallback works when tests run from intellij/. */
    val dir: Path = Path.of(System.getProperty("copyPasta.vectors") ?: "../spec/vectors").toAbsolutePath().normalize()
    val manifest: Path get() = dir.resolve("manifest.json")
    val modes: Path get() = dir.resolve("modes.json")

    class ExpectedFile(val path: String, val bytes: ByteArray, val mode: Int?, val executable: Boolean)

    class Case(
        val name: String,
        val description: String,
        val file: String,
        val passphrase: String?,
        val error: String?,
        val files: List<ExpectedFile>?,
        val encrypted: Boolean?,
        val blocked: List<String>,
        val blockedOnWindows: List<String>,
        val summary: String?,
        val maxMillis: Long?,
    ) {
        override fun toString() = name

        fun text(): String = Files.readString(dir.resolve(file))
    }

    class ModeRule(val sender: Int, val current: Int, val safe: Int, val applied: Int)

    fun octal(mode: Int?): String? = mode?.let { "0" + it.toString(8).padStart(3, '0') }

    private fun parseOctal(text: String): Int = text.toInt(8)

    fun loadCases(): List<Case> {
        val root = JsonParser.parseString(Files.readString(manifest)).asJsonObject
        return root.getAsJsonArray("cases").map { element ->
            val case = element.asJsonObject
            val expect = case.getAsJsonObject("expect")
            Case(
                name = case["name"].asString,
                description = case["description"].asString,
                file = case["file"].asString,
                passphrase = case.stringOrNull("passphrase"),
                error = expect.stringOrNull("error"),
                files =
                expect.getAsJsonArray("files")?.map {
                    val file = it.asJsonObject
                    ExpectedFile(
                        path = file["path"].asString,
                        bytes = Base64.getDecoder().decode(file["content"].asString),
                        mode = file.stringOrNull("mode")?.let(::parseOctal),
                        executable = file["executable"].asBoolean,
                    )
                },
                encrypted = expect["encrypted"]?.takeUnless { it.isJsonNull }?.asBoolean,
                blocked = expect.getAsJsonArray("blocked")?.map { it.asString }.orEmpty(),
                blockedOnWindows = expect.getAsJsonArray("blockedOnWindows")?.map { it.asString }.orEmpty(),
                summary = expect.stringOrNull("summary"),
                maxMillis = case["maxMillis"]?.takeUnless { it.isJsonNull }?.asLong,
            )
        }
    }

    fun loadModeRules(): List<ModeRule> = JsonParser.parseString(Files.readString(modes)).asJsonObject.getAsJsonArray("rules").map {
        val rule = it.asJsonObject
        ModeRule(
            parseOctal(rule["sender"].asString),
            parseOctal(rule["current"].asString),
            parseOctal(rule["safe"].asString),
            parseOctal(rule["applied"].asString),
        )
    }

    /** The plugin's decode pipeline, as PasteFlow runs it: envelope → decrypt (if encrypted) → ZIP. */
    fun decodeAll(text: String, passphrase: String?): List<FileEntry> {
        val decoded = Envelope.decode(text)
        val zip = if (decoded.encrypted) Crypto.decrypt(decoded.payload, (passphrase ?: "").toCharArray()) else decoded.payload
        return ZipCodec.decode(zip)
    }

    /** The spec's error category for a failure of [decodeAll]. */
    fun category(error: Throwable): String = when (error) {
        is Envelope.EnvelopeException -> error.problem.name
        is WrongPassphraseException -> "WRONG_PASSPHRASE"
        is InvalidArchiveException -> "INVALID_ARCHIVE"
        else -> "UNEXPECTED ${error::class.simpleName}: ${error.message}"
    }

    fun writeJson(path: Path, json: JsonObject) {
        Files.writeString(path, GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create().toJson(json) + "\n")
    }

    fun jsonFile(file: FileEntry) = JsonObject().apply {
        addProperty("path", file.path)
        addProperty("content", Base64.getEncoder().encodeToString(file.bytes))
        addProperty("mode", octal(file.mode))
        addProperty("executable", file.executable)
    }

    fun jsonArray(items: List<String>) = JsonArray().apply { items.forEach(::add) }

    val NULL: JsonNull = JsonNull.INSTANCE

    private fun JsonObject.stringOrNull(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.asString
}
