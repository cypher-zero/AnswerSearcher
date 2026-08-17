package com.answersearcher.app

import com.answersearcher.app.model.ExcelData
import com.answersearcher.app.model.QAPair

/**
 * 清洗文本：仅保留中文 / 字母 / 数字，去掉换行、空格、标点等干扰符号。
 * 这样无论 OCR 怎么断行、怎么混入标点，题目文字都会被连成一条连续串，
 * 后续用 LCS 在题库题面中找这条串的最长公共部分。
 * 顶层函数，供 ExcelManager 加载时预计算复用。
 */
fun cleanText(text: String): String {
    val sb = StringBuilder(text.length)
    for (ch in text) {
        if (ch in KEEP_CHARS) sb.append(ch)
    }
    return sb.toString()
}

/** 清洗时保留的字符集合（中文 / 大小写字母 / 数字），O(1) 判定 */
private val KEEP_CHARS: Set<Char> = buildSet {
    for (c in 'a'..'z') add(c)
    for (c in 'A'..'Z') add(c)
    for (c in '0'..'9') add(c)
    for (c in '\u4e00'..'\u9fa5') add(c)
}

/**
 * 模糊搜索引擎
 *
 * 核心思路：
 * - OCR 识别天然会有个别错别字 / 漏字，因此用"最长公共子序列(LCS)"相似度：
 *   只要题面有 ~70% 的汉字能在 OCR 文本中按顺序命中（允许中间有少量错/漏字），
 *   即判定为匹配。对 OCR 噪音高度容忍。
 * - 题库题面的清洗结果与字符集在加载时预计算（见 QAPair.cleanQuestion /
 *   QAPair.questionCharSet），搜索时不再对每道题重复正则，2597 行题库下耗时可降低一个量级。
 * - 廉价预筛：题面中至少 60% 的汉字要在 OCR 文本里出现过，否则直接跳过 LCS 计算。
 */
object SearchEngine {

    /**
     * 搜索答案
     * @param ocrText OCR 识别出的文本
     * @param excelData 题库数据
     * @return 匹配到的问答对，未找到返回 null
     */
    fun search(ocrText: String, excelData: ExcelData): QAPair? {
        val co = cleanText(ocrText)
        if (co.length < 6) return null

        val coSet = co.toSet()

        var bestPair: QAPair? = null
        var bestScore = 0

        for (pair in excelData.pairs) {
            val cq = pair.cleanQuestion
            if (cq.length < 4) continue

            // 廉价预筛：题面中至少 60% 的汉字要在 OCR 文本里出现过，否则直接跳过，省去 DP 计算
            val present = pair.questionCharSet.count { it in coSet }
            if (present < cq.length * 0.6) continue

            val lcs = lcsLength(co, cq)

            // 匹配阈值：最长公共子序列长度需 >= max(6, 题面长度 * 0.7)
            // 即允许约 30% 的错/漏字，兼顾 OCR 噪音与误匹配。
            val needed = maxOf(6, (cq.length * 0.7).toInt())
            if (lcs >= needed && lcs > bestScore) {
                bestScore = lcs
                bestPair = pair
            }
        }

        return bestPair
    }

    /**
     * 最长公共子序列长度（动态规划）。
     * 与"最长公共子串"不同，LCS 允许中间有间隔，因此对 OCR 漏字 / 错别字非常鲁棒。
     */
    private fun lcsLength(a: String, b: String): Int {
        val n = a.length
        val m = b.length
        // 用滚动数组，空间 O(m)
        val dp = IntArray(m + 1)
        for (i in 1..n) {
            var prev = 0
            val ai = a[i - 1]
            for (j in 1..m) {
                val temp = dp[j]
                dp[j] = if (ai == b[j - 1]) prev + 1 else maxOf(dp[j], dp[j - 1])
                prev = temp
            }
        }
        return dp[m]
    }
}
