package app.shizheng.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class BackupArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    // JVM tests cover archive integrity. Android device tests exercise format parsers.
    private val archive = BackupArchive { _, _ -> }

    @Test fun roundTripPreservesAssociationsOrderMetadataAndOriginalBytes() {
        val fixture = prepareFixture()
        archive.prepare(encoded(fixture).inputStream(), temp.root).use { incoming ->
            assertEquals(fixture.first, incoming.snapshot)
            val records = incoming.snapshot.records
            assertEquals(listOf("图片.png", "材料.pdf", "活动说明.docx"), incoming.snapshot.filesFor(records.first().id).map(Evidence::name))
            for (item in incoming.snapshot.evidence) {
                assertArrayEquals(fixture.second.getValue(item.id).readBytes(), incoming.attachment(item).readBytes())
            }
        }
    }

    @Test fun rejectsChangedBytesMissingEntriesAndTraversalAndCleansStaging() {
        val zip = entries(encoded(prepareFixture()))
        val attachment = zip.keys.first { it.startsWith("evidence/") }
        val first = zip.getValue(attachment)
        val corrupt = first.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        val restoreDir = temp.newFolder()
        val variants = listOf(zip + (attachment to corrupt), zip - attachment, zip + ("../escaped" to first))
        variants.forEach { changed ->
            assertThrows(UserError::class.java) { archive.prepare(zipped(changed).inputStream(), restoreDir) }
            assertArrayEquals(emptyArray<File>(), restoreDir.listFiles())
        }
        assertFalse(File(temp.root, "escaped").exists())
    }

    @Test fun rejectsFutureVersionsAndMissingLists() {
        val folder = temp.newFolder()
        val version = zipped(mapOf("manifest.json" to
            """{"format":"app.shizheng.backup","version":99,"data":{"futureField":1}}""".encodeToByteArray()))
        val error = assertThrows(UserError::class.java) { archive.prepare(version.inputStream(), folder) }
        assertTrue(error.message.orEmpty().contains("版本"))
        val absent = zipped(mapOf("manifest.json" to
            """{"format":"app.shizheng.backup","version":1,"data":{}}""".encodeToByteArray()))
        assertThrows(UserError::class.java) { archive.prepare(absent.inputStream(), folder) }
    }

    @Test fun rejectsInvalidAssociationAndOutOfOrderMetadata() {
        val (snapshot, files) = prepareFixture()
        val file = snapshot.evidence.first()
        val remaining = snapshot.evidence.drop(1)
        for (bad in listOf(file.copy(recordId = newId()), file.copy(order = 99))) {
            assertThrows(UserError::class.java) {
                archive.write(snapshot.copy(evidence = listOf(bad) + remaining), ByteArrayOutputStream()) { files.getValue(it.id) }
            }
        }
    }

    @Test fun restoresVersionOneAndRoundTripsUndatedRecordsWithRetiredCategories() {
        val fixture = prepareFixture()
        val training = Category.TRAINING
        val oldRecord = fixture.first.records.single().copy(category = training.id)
        val oldSnapshot = fixture.first.copy(records = listOf(oldRecord), categories = legacyCategories)
        val oldZip = encoded(oldSnapshot to fixture.second)
        archive.prepare(oldZip.inputStream(), temp.root).use { assertEquals(oldSnapshot, it.snapshot) }

        val zip = entries(oldZip)
        val manifest = Json.parseToJsonElement(zip.getValue("manifest.json").decodeToString()).jsonObject
        val oldData = JsonObject(manifest.getValue("data").jsonObject - "categories")
        val oldManifest = JsonObject(manifest + mapOf("version" to JsonPrimitive(1), "data" to oldData))
        val legacy = zipped(zip + ("manifest.json" to oldManifest.toString().encodeToByteArray()))
        archive.prepare(legacy.inputStream(), temp.root).use {
            val updated = oldSnapshot.copy(categories = defaultCategories + RecordCategory(training.id, training.label, active = false))
            assertEquals(updated, it.snapshot)
        }

        val category = RecordCategory(newId(), "历史自定义类别", false)
        val record = fixture.first.records.single().copy(category = category.id, start = "", end = null)
        val updated = fixture.first.copy(records = listOf(record), categories = defaultCategories + category)
        archive.prepare(encoded(updated to fixture.second).inputStream(), temp.root).use {
            assertEquals(updated, it.snapshot)
        }
    }

    private fun prepareFixture(): Pair<Snapshot, Map<String, File>> {
        val term = Term(newId(), "秋季学期", "2026-09-01", "2027-01-31", true)
        val record = RecordDraft(title = "志愿活动", category = Category.VOLUNTEER.name, start = "2026-11-24", termId = term.id,
            level = Level.SCHOOL, organization = "实践部", result = "服务8小时", notes = "合成用例").toRecord(listOf(term))
        val files = linkedMapOf<String, File>()
        val items = listOf("图片.png" to EvidenceKind.PHOTO, "材料.pdf" to EvidenceKind.PDF, "活动说明.docx" to EvidenceKind.DOCX)
            .mapIndexed { order, (name, kind) ->
                val file = temp.newFile("binary-$order").apply { writeBytes(ByteArray(2048 + order) { (it * order).toByte() }) }
                val digest = file.inputStream().use { copyAndHash(it, java.io.OutputStream.nullOutputStream(), ATTACHMENT_LIMIT) }
                Evidence(newId(), record.id, name, kind, order, digest.size, digest.sha256).also { files[it.id] = file }
            }
        return Snapshot(listOf(term), listOf(record), items) to files
    }

    private fun encoded(fixture: Pair<Snapshot, Map<String, File>>): ByteArray = ByteArrayOutputStream().also { out ->
        archive.write(fixture.first, out) { fixture.second.getValue(it.id) }
    }.toByteArray()

    private fun entries(zip: ByteArray): Map<String, ByteArray> = ZipInputStream(zip.inputStream()).use { input ->
        buildMap {
            while (true) {
                val entry = input.nextEntry ?: break
                put(entry.name, input.readBytes())
            }
        }
    }

    private fun zipped(contents: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in contents) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()
}
