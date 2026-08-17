package com.answersearcher.app.model

/**
 * Excel 问答数据模型
 * @param question 题目列内容（原始）
 * @param answer   答案列内容
 * @param cleanQuestion 清洗后的题目（仅保留中文/字母/数字），搜索时直接使用，避免每轮重复正则
 * @param questionCharSet cleanQuestion 的字符集合，用于廉价预筛
 */
data class QAPair(
    val question: String,
    val answer: String,
    val cleanQuestion: String,
    val questionCharSet: Set<Char>
)

/**
 * Excel 表格数据
 * @param pairs    所有问答对
 * @param headers  表头
 */
data class ExcelData(
    val pairs: List<QAPair>,
    val headers: List<String>
)
