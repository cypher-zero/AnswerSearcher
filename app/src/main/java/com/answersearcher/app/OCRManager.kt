package com.answersearcher.app

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * OCR 文字识别管理器
 * 使用 ML Kit 中文识别 (on-device, 低延迟)
 */
object OCRManager {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * 识别图片中的中文文字
     * @param bitmap 待识别的图片
     * @return 识别出的文本
     */
    suspend fun recognizeText(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    // 拼接所有识别到的文本块
                    val sb = StringBuilder()
                    for (block in visionText.textBlocks) {
                        sb.append(block.text).append("\n")
                    }
                    cont.resume(sb.toString().trim())
                }
                .addOnFailureListener { e ->
                    cont.resume("")
                }
        }
}
