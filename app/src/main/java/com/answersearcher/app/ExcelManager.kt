package com.answersearcher.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.answersearcher.app.model.ExcelData
import com.answersearcher.app.model.QAPair
import com.answersearcher.app.cleanText
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 题库读取器
 * - 支持 CSV（Excel 可另存为）与 XLSX（现代 Excel）两种格式
 * - 解析后返回「表格数据」(TableData)，由调用方决定哪一列是题目、哪一列是答案（列映射）
 */
object ExcelManager {

    /**
     * 解析后的整张表（含首行，首行通常作为列标题由 UI 展示）
     */
    data class TableData(
        val fileName: String,
        val sheetName: String,
        val rows: List<List<String>>
    )

    /**
     * 根据文件扩展名选择解析器，返回整张表数据。
     */
    fun loadTable(context: Context, uri: Uri): TableData {
        val name = getFileName(context, uri)
        val lower = name.lowercase()
        return if (lower.endsWith(".xlsx")) {
            val xlsx = XlsxReader.read(context.contentResolver.openInputStream(uri)!!)
            TableData(name, xlsx.sheetName, xlsx.rows)
        } else {
            // 默认按 CSV 处理（.csv 及其它文本表格）
            TableData(name, "CSV", parseCsvAllRows(context, uri))
        }
    }

    /**
     * 按列映射（题目列 qCol、答案列 aCol）与是否含表头，把表格数据转为问答对。
     * 若 hasHeader 为 true，则首行作为标题、不计入数据；否则全部行都作为数据。
     */
    fun buildExcelData(table: TableData, qCol: Int, aCol: Int, hasHeader: Boolean): ExcelData {
        val dataRows = if (hasHeader) table.rows.drop(1) else table.rows
        val pairs = mutableListOf<QAPair>()
        for (row in dataRows) {
            val q = row.getOrNull(qCol)?.trim() ?: ""
            val a = row.getOrNull(aCol)?.trim() ?: ""
            if (q.isNotBlank()) pairs.add(buildPair(q, a))
        }
        val headers = if (hasHeader) table.rows.firstOrNull().orEmpty() else emptyList()
        return ExcelData(pairs, headers)
    }

    /**
     * 读取 CSV 的全部行（每行按逗号切分为列，支持引号包裹与字段内逗号）。
     */
    private fun parseCsvAllRows(context: Context, uri: Uri): List<List<String>> {
        val out = mutableListOf<List<String>>()
        context.contentResolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                reader.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    out.add(parseCSVLine(line))
                }
            }
        }
        return out
    }

    /**
     * 构造问答对，并预计算清洗题面与字符集（搜索时复用，避免每轮重复正则）
     */
    fun buildPair(question: String, answer: String): QAPair {
        val clean = cleanText(question)
        return QAPair(question, answer, clean, clean.toSet())
    }

    /**
     * 解析 CSV 行（支持引号包裹、字段内逗号）
     */
    fun parseCSVLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == ',' && !inQuotes -> {
                    result.add(sb.toString())
                    sb.clear()
                }
                else -> sb.append(c)
            }
            i++
        }
        result.add(sb.toString())
        return result
    }

    fun getFileName(context: Context, uri: Uri): String {
        var name = "unknown"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }
}
