package app.shizheng

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.shizheng.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class StorageDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun importsRealFilesReopensDbAndRestoresOriginalBytesAfterADamagedBackup() = runBlocking {
        check(context.packageName.endsWith(".debug"))
        val repository = RecordRepository(context)
        repository.initialize()
        val baseline = repository.data.value
        val term = Term(newId(), "设备测试学期", "2026-09-01", "2027-01-31", true)
        repository.saveTerm(term)
        val draft = RecordDraft(title = "设备验证记录", category = Category.VOLUNTEER.name, level = Level.SCHOOL, termId = term.id,
            start = "2026-11-24", ranged = true, end = "2026-11-25", organization = "实验组", result = "参与", notes = "合成样例")
        repository.save(draft)
        assertTrue(RecordFilter(state = ArchiveState.PENDING).apply(repository.data.value).any { it.id == draft.id })
        val cameraDir = repository.evidence.cameraFile().let { it.delete(); requireNotNull(it.parentFile) }
        val sources = fixtures(cameraDir)
        val original = sources.associate { it.name to it.readBytes() }
        val added = sources.mapIndexed { index, file ->
            repository.evidence.import(draft.id, repository.evidence.uri(file), index)
        }
        val arranged = listOf(added[2].copy(name = "已核对的说明.docx"), added[0], added[1])
        repository.save(draft.copy(files = arranged, notes = "已编辑备注"))
        assertEquals(added.map { it.kind }, listOf(EvidenceKind.PHOTO, EvidenceKind.PDF, EvidenceKind.DOCX))
        assertTrue(RecordFilter("实验组", Category.VOLUNTEER.name, Level.SCHOOL, term.id, ArchiveState.ARCHIVED)
            .apply(repository.data.value).any { it.id == draft.id })
        for (source in sources) assertArrayEquals(original.getValue(source.name), source.readBytes())
        val restarted = RecordRepository(context)
        restarted.initialize()
        val before = restarted.data.value
        val persisted = before.records.single { it.id == draft.id }
        assertEquals("已编辑备注", persisted.notes)
        assertEquals(term.id, persisted.termId)
        assertEquals(arranged.map(Evidence::name), before.filesFor(draft.id).map(Evidence::name))
        decodePhoto(restarted.evidence.file(added[0]), 180).also {
            assertTrue(it.width > 0)
            it.recycle()
        }
        openPdf(restarted.evidence.file(added[1])).use { viewer ->
            assertEquals(2, viewer.pageCount)
            viewer.openPage(1).use { assertTrue(it.width > 0) }
        }
        val exported = added.mapIndexed { index, item ->
            val (name, mime) = restarted.evidence.exportNameAndType(item.copy(name = "另存"))
            assertEquals(listOf("image/png", "application/pdf", EvidenceKind.DOCX.mime)[index], mime)
            val target = File(cameraDir, name)
            restarted.evidence.copyTo(item, restarted.evidence.uri(target))
            assertArrayEquals(original.getValue(sources[index].name), target.readBytes())
            target
        }

        val selectedExport = File(cameraDir, "所选记录.zip")
        assertEquals(1, restarted.exportRecords(restarted.evidence.uri(selectedExport), setOf(draft.id)))
        ZipFile(selectedExport).use { zip ->
            val names = zip.entries().toList().map(ZipEntry::getName)
            assertEquals(listOf("记录清单.csv", "证明附件/1/1-已核对的说明.docx",
                "证明附件/1/2-照片.png", "证明附件/1/3-材料.pdf"), names)
            val csv = zip.getInputStream(zip.getEntry(names.first())).bufferedReader().use { it.readText() }
            assertTrue(csv.startsWith("\uFEFF"))
            for (value in listOf("设备验证记录", "设备测试学期", "已编辑备注", "证明附件/1")) {
                assertTrue(csv.contains("\"$value\""))
            }
            names.drop(1).zip(listOf(sources[2], sources[0], sources[1])).forEach { (name, source) ->
                assertArrayEquals(original.getValue(source.name), zip.getInputStream(zip.getEntry(name)).use { it.readBytes() })
            }
        }
        assertTrue(runCatching { restarted.inspectBackup(restarted.evidence.uri(selectedExport)) }.exceptionOrNull() is UserError)
        assertEquals(before, restarted.data.value)

        val backup = File(cameraDir, "设备完整备份.zip")
        restarted.backupTo(restarted.evidence.uri(backup))
        val broken = File(cameraDir, "损坏备份.zip").apply { writeBytes(backup.readBytes().take(50).toByteArray()) }
        assertTrue(runCatching { restarted.inspectBackup(restarted.evidence.uri(broken)) }.exceptionOrNull() is UserError)
        assertEquals(before, restarted.data.value)

        val prepared = restarted.inspectBackup(restarted.evidence.uri(backup))
        assertEquals(before, prepared.snapshot)
        for ((index, file) in before.filesFor(draft.id).withIndex()) {
            assertArrayEquals(restarted.evidence.file(file).readBytes(), prepared.attachment(arranged[index]).readBytes())
        }
        restarted.save(RecordDraft.from(persisted, listOf(arranged.last())))
        restarted.save(RecordDraft.from(persisted, emptyList()))
        assertTrue(RecordFilter(state = ArchiveState.PENDING).apply(restarted.data.value).any { it.id == draft.id })
        assertFalse(restarted.evidence.file(added[1]).exists())
        restarted.replace(prepared)
        val restored = restarted.data.value
        assertEquals(before.terms, restored.terms)
        assertEquals(before.records.toSet(), restored.records.toSet())
        val restoredFiles = restored.filesFor(draft.id)
        assertEquals(before.filesFor(draft.id).map { it.copy(id = "") }, restoredFiles.map { it.copy(id = "") })
        assertArrayEquals(original.getValue("说明.docx"), restarted.evidence.file(restoredFiles[0]).readBytes())
        assertArrayEquals(original.getValue("照片.png"), restarted.evidence.file(restoredFiles[1]).readBytes())
        assertArrayEquals(original.getValue("材料.pdf"), restarted.evidence.file(restoredFiles[2]).readBytes())
        restarted.deleteRecord(draft.id)
        assertFalse(restarted.data.value.records.any { it.id == draft.id })
        assertTrue(restoredFiles.none { restarted.evidence.file(it).exists() })
        assertArrayEquals(original.getValue("说明.docx"), exported.last().readBytes())
        // Any records present before the exercise remain in the debug installation.
        assertEquals(baseline.records.map(Record::id).toSet(), restarted.data.value.records.map(Record::id).toSet())
    }

    private fun fixtures(directory: File): List<File> {
        val photo = File(directory, "照片.png")
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        photo.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        val pdf = File(directory, "材料.pdf")
        val document = PdfDocument()
        try {
            for (i in 1..2) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(210, 297, i).create())
                page.canvas.drawText("Test page $i", 30f, 90f, Paint())
                document.finishPage(page)
            }
            pdf.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
        val word = File(directory, "说明.docx")
        ZipOutputStream(word.outputStream()).use { zip ->
            val files = mapOf(
                "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""",
                "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
                "word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Test fixture</w:t></w:r></w:p></w:body></w:document>"""
            )
            for ((name, text) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.encodeToByteArray())
                zip.closeEntry()
            }
        }
        return listOf(photo, pdf, word)
    }
}
