package com.answersearcher.app

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 轻量 XLSX 读取器（仅依赖 Android 内置 API，不引入第三方库）。
 * 支持：读取第一个工作表，解析共享字符串（sharedStrings）与单元格（含数字 / 共享字符串 / 内联字符串）。
 * 适用常见由 Excel 导出的 .xlsx；不支持旧版二进制 .xls。
 */
object XlsxReader {

    data class XlsxTable(val sheetName: String, val rows: List<List<String>>)

    @Throws(IOException::class, XmlPullParserException::class)
    fun read(input: InputStream): XlsxTable {
        val sharedStrings = mutableListOf<String>()
        var firstSheetName = "Sheet1"
        var firstSheetRid: String? = null
        val rels = mutableMapOf<String, String>() // Id -> Target
        val sheets = mutableMapOf<String, ByteArray>() // 路径 -> 字节

        val zis = ZipInputStream(input)
        var entry = zis.nextEntry
        while (entry != null) {
            val name = entry.name
            when {
                name == "xl/sharedStrings.xml" -> {
                    sharedStrings.addAll(parseSharedStrings(zis))
                }
                name == "xl/workbook.xml" -> {
                    val (n, rid) = parseWorkbook(zis)
                    if (firstSheetRid == null) {
                        firstSheetName = n
                        firstSheetRid = rid
                    }
                }
                name == "xl/_rels/workbook.xml.rels" -> {
                    rels.putAll(parseRels(zis))
                }
                name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml") -> {
                    if (!sheets.containsKey(name)) sheets[name] = zis.readBytes()
                }
            }
            zis.closeEntry()
            entry = zis.nextEntry
        }
        zis.close()

        val target = firstSheetRid?.let { rels[it] }
        val sheetPath = if (target != null) {
            if (target.startsWith("/")) target.substring(1) else "xl/$target"
        } else {
            sheets.keys.firstOrNull()
        }
        val sheetBytes = (if (sheetPath != null) sheets[sheetPath] else null)
            ?: sheets.values.firstOrNull()
            ?: throw IOException("未找到工作表数据")
        val rows = parseSheet(sheetBytes.inputStream(), sharedStrings)
        return XlsxTable(firstSheetName, rows)
    }

    private fun parseSharedStrings(stream: InputStream): List<String> {
        val list = mutableListOf<String>()
        val parser = Xml.newPullParser()
        parser.setInput(stream, "UTF-8")
        var text = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "si" -> text = StringBuilder()
                        "t" -> text.append(parser.nextText())
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "si") list.add(text.toString())
                }
            }
            event = parser.next()
        }
        return list
    }

    private fun parseWorkbook(stream: InputStream): Pair<String, String?> {
        val parser = Xml.newPullParser()
        parser.setInput(stream, "UTF-8")
        var name = "Sheet1"
        var rid: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "sheet") {
                name = parser.getAttributeValue(null, "name") ?: name
                rid = parser.getAttributeValue(
                    "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id"
                )
            }
            event = parser.next()
        }
        return Pair(name, rid)
    }

    private fun parseRels(stream: InputStream): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val parser = Xml.newPullParser()
        parser.setInput(stream, "UTF-8")
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "Relationship") {
                val id = parser.getAttributeValue(null, "Id")
                val target = parser.getAttributeValue(null, "Target")
                if (id != null && target != null) map[id] = target
            }
            event = parser.next()
        }
        return map
    }

    private fun parseSheet(stream: InputStream, sharedStrings: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val parser = Xml.newPullParser()
        parser.setInput(stream, "UTF-8")
        var curRow = mutableMapOf<Int, String>()
        var curCol = -1
        var curType: String? = null
        var curText = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "row" -> {
                            curRow = mutableMapOf()
                            curCol = -1
                            curType = null
                        }
                        "c" -> {
                            curCol = colToIndex(parser.getAttributeValue(null, "r") ?: "")
                            curType = parser.getAttributeValue(null, "t")
                            curText = StringBuilder()
                        }
                        "v", "t" -> curText = StringBuilder()
                    }
                }
                XmlPullParser.TEXT -> curText.append(parser.text)
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "v" -> {
                            val raw = curText.toString()
                            val value = when (curType) {
                                "s" -> sharedStrings.getOrNull(raw.toIntOrNull() ?: -1) ?: ""
                                else -> raw
                            }
                            if (curCol >= 0) curRow[curCol] = value
                        }
                        "t" -> {
                            if (curType == "inlineStr" && curCol >= 0) {
                                curRow[curCol] = curText.toString()
                            }
                        }
                        "c" -> {
                            curCol = -1
                            curType = null
                        }
                        "row" -> {
                            val maxCol = curRow.keys.maxOrNull() ?: -1
                            val list = MutableList(maxCol + 1) { curRow[it] ?: "" }
                            rows.add(list)
                        }
                    }
                }
            }
            event = parser.next()
        }
        return rows
    }

    private fun colToIndex(ref: String): Int {
        var idx = 0
        for (c in ref) {
            idx = when (c) {
                in 'A'..'Z' -> idx * 26 + (c - 'A' + 1)
                in 'a'..'z' -> idx * 26 + (c - 'a' + 1)
                else -> break
            }
        }
        return idx - 1
    }
}
