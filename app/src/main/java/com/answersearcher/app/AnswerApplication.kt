package com.answersearcher.app

import android.app.Application
import com.answersearcher.app.model.ExcelData

class AnswerApplication : Application() {

    companion object {
        // 全局状态：已加载的问答数据（由用户在 App 内「选择题库」导入，不再内置）
        @Volatile
        var excelData: ExcelData? = null

        // 当前题库显示名（用于主界面状态展示）
        @Volatile
        var currentBankName: String? = null
    }

    override fun onCreate() {
        super.onCreate()
        // 不再内置题库：题库由用户在 MainActivity 中通过「选择题库」导入并持久化。
    }
}
