package com.pittech.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pittech.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class WorkbookIntegrationTest {
    private fun xml(bytes: ByteArray): Document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    private fun sheets(bytes: ByteArray): Map<String, Document> {
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip -> while (true) { val entry = zip.nextEntry ?: break; files[entry.name] = zip.readBytes() } }
        val header = xml(files.getValue("xl/workbook.xml")); val names = header.getElementsByTagNameNS("*", "sheet")
        return (0 until names.length).associate { index -> val sheet = names.item(index) as Element; sheet.getAttribute("name") to xml(files.getValue("xl/worksheets/sheet${sheet.getAttribute("sheetId")}.xml")) }
    }
    private fun cells(sheet: Document): Map<String, Element> {
        val cells = sheet.getElementsByTagNameNS("*", "c")
        return (0 until cells.length).associate { index -> val cell = cells.item(index) as Element; cell.getAttribute("r") to cell }
    }
    @Test fun numericCelsiusLiteralFormulasLongUnicodeAndIdenticallyNamedChannelsRemainDistinct(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val database = Room.inMemoryDatabaseBuilder(context, PitTechDatabase::class.java).build()
        try {
            val repository = CookRepository(database, PhotoStorage(context)); val transfer = PitTechDataTransfer(context, repository)
            val cook = repository.startCook(NewCookDraft("Pork", dishes = listOf(DishDraft("Pork", "Pork"))))
            val dish = database.cookDao().getDishesForCook(cook).single()
            val probes = listOf("first", "second").map { ProbeEntity(it, cook, assignedDishId = dish.id, name = "=Probe,\n\"same\"", measurementType = "food", source = "manual", createdAtUtcMillis = 1700000000000) }
            database.cookDao().insertProbesIgnoringDuplicates(probes)
            repository.notes.save(CookNoteDraft(cookId = cook, dishId = dish.id, title = "Measured", temperatures = probes.mapIndexed { index, probe -> NoteTemperature(probeName = probe.name, probeId = probe.id, valueText = if (index == 0) "74.125" else "84.375", unit = "°C", measuredAt = 1700000000123 + index, dishId = dish.id) }))
            val text = "🌶".repeat(17000) + "Line, \"quoted\"\nNext"
            database.cookDao().updateCook(database.cookDao().getCook(cook)!!.copy(notes = text))
            repository.startCook(NewCookDraft("Unrelated meal"))
            val output = ByteArrayOutputStream(); transfer.writeWorkbook(output, cook)
            val sheets = sheets(output.toByteArray()); assertEquals(19, sheets.size)
            val readings = cells(sheets.getValue("Readings"))
            assertEquals("74.125", readings.getValue("F2").textContent); assertEquals("", readings.getValue("F2").getAttribute("t"))
            assertEquals("=Probe,\n\"same\"", readings.getValue("D2").textContent); assertEquals("inlineStr", readings.getValue("D2").getAttribute("t")); assertEquals("°C", readings.getValue("G2").textContent)
            assertEquals(0, sheets.getValue("Readings").getElementsByTagNameNS("*", "f").length)
            val summary = cells(sheets.getValue("Cook Summary")); assertEquals("Pork", summary.getValue("B2").textContent); assertFalse(summary.containsKey("B3"))
            val chunks = cells(sheets.getValue("Long Values")); assertEquals(text, chunks.getValue("D2").textContent + chunks.getValue("D3").textContent)
            chunks.filter { it.key.startsWith("D") }.values.forEach { assertTrue(it.textContent.length <= 32767) }
            val metrics = cells(sheets.getValue("Derived Metrics")); assertEquals(2, metrics.filter { it.key.startsWith("C") && it.value.textContent == "reading_count" }.size)
            val csv = ByteArrayOutputStream(); transfer.writeCsv(csv, cook); assertTrue(csv.toString("UTF-8").contains("\"'=Probe,\n\"\"same\"\"\""))
        } finally { database.close() }
    }
}
