package com.answersearcher.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.answersearcher.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // 截屏权限回调
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            FloatingWindowService.mediaProjectionResultCode = result.resultCode
            FloatingWindowService.mediaProjectionData = result.data
            startFloatingWindow()
        } else {
            Toast.makeText(this, R.string.capture_permission_needed, Toast.LENGTH_SHORT).show()
        }
    }

    // 选择题库：文件选择回调
    private val pickBankLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val uri = result.data!!.data ?: return@registerForActivityResult
            onBankPicked(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        updateQuestionBankStatus()
        restoreBank()
    }

    private fun setupUI() {
        binding.btnChooseBank.setOnClickListener { openFilePicker() }
        binding.btnStartFloating.setOnClickListener { onStartFloatingClicked() }
    }

    // ---------- 选择题库 ----------

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        }
        pickBankLauncher.launch(intent)
    }

    private fun onBankPicked(uri: Uri) {
        val name = ExcelManager.getFileName(this, uri)
        val lower = name.lowercase()
        if (lower.endsWith(".xls") && !lower.endsWith(".xlsx")) {
            Toast.makeText(this, R.string.bank_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        // 申请跨重启的持久读取权限
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) { /* 部分提供方不支持，忽略 */ }

        lifecycleScope.launch {
            try {
                val table = withContext(Dispatchers.IO) {
                    ExcelManager.loadTable(this@MainActivity, uri)
                }
                withContext(Dispatchers.Main) { showMappingDialog(uri, table) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.bank_load_failed, e.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * 列映射对话框：让用户选择哪一列是题目、哪一列是答案，并指定首行是否为表头。
     */
    private fun showMappingDialog(uri: Uri, table: ExcelManager.TableData) {
        val columnCount = table.rows.maxOfOrNull { it.size } ?: 0
        if (columnCount == 0) {
            Toast.makeText(this, R.string.bank_empty, Toast.LENGTH_LONG).show()
            return
        }
        val headerRow = table.rows.firstOrNull().orEmpty()

        fun labelFor(i: Int, hasHeader: Boolean): String {
            val h = if (hasHeader) headerRow.getOrNull(i)?.trim() else ""
            return if (!h.isNullOrBlank()) "$h（第${i + 1}列）" else "第${i + 1}列"
        }

        val view = layoutInflater.inflate(
            R.layout.dialog_column_map, null
        )
        val spQ = view.findViewById<Spinner>(R.id.spinnerQuestionCol)
        val spA = view.findViewById<Spinner>(R.id.spinnerAnswerCol)
        val cbHeader = view.findViewById<CheckBox>(R.id.cbHasHeader)

        val adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            (0 until columnCount).map { labelFor(it, true) }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spQ.adapter = adapter
        spA.adapter = adapter
        spQ.setSelection(0)
        spA.setSelection(if (columnCount > 1) 1 else 0)

        cbHeader.setOnCheckedChangeListener { _, checked ->
            adapter.clear()
            adapter.addAll((0 until columnCount).map { labelFor(it, checked) })
            adapter.notifyDataSetChanged()
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.column_map_title)
            .setView(view)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val hasHeader = cbHeader.isChecked
                val qCol = spQ.selectedItemPosition
                val aCol = spA.selectedItemPosition
                applyMapping(uri, table, qCol, aCol, hasHeader)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyMapping(
        uri: Uri,
        table: ExcelManager.TableData,
        qCol: Int,
        aCol: Int,
        hasHeader: Boolean
    ) {
        val data = ExcelManager.buildExcelData(table, qCol, aCol, hasHeader)
        if (data.pairs.isEmpty()) {
            Toast.makeText(this, R.string.bank_empty, Toast.LENGTH_LONG).show()
            return
        }
        AnswerApplication.excelData = data
        AnswerApplication.currentBankName = table.fileName
        BankPrefs.save(
            this,
            BankPrefs.BankPref(
                uri = uri.toString(),
                qCol = qCol,
                aCol = aCol,
                hasHeader = hasHeader,
                fileName = table.fileName
            )
        )
        updateQuestionBankStatus()
        Toast.makeText(
            this,
            getString(R.string.bank_loaded_named, table.fileName, data.pairs.size),
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * 启动时若已保存过题库，则自动重新加载（无需每次手动选择）。
     */
    private fun restoreBank() {
        lifecycleScope.launch {
            val pref = BankPrefs.load(this@MainActivity) ?: run {
                updateQuestionBankStatus()
                return@launch
            }
            updateQuestionBankStatus(restoring = true)
            try {
                val uri = Uri.parse(pref.uri)
                val table = withContext(Dispatchers.IO) {
                    ExcelManager.loadTable(this@MainActivity, uri)
                }
                val data = ExcelManager.buildExcelData(table, pref.qCol, pref.aCol, pref.hasHeader)
                if (data.pairs.isEmpty()) {
                    BankPrefs.clear(this@MainActivity)
                    withContext(Dispatchers.Main) {
                        updateQuestionBankStatus()
                        Toast.makeText(this@MainActivity, R.string.bank_empty, Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                AnswerApplication.excelData = data
                AnswerApplication.currentBankName = pref.fileName
                withContext(Dispatchers.Main) { updateQuestionBankStatus() }
            } catch (e: Exception) {
                BankPrefs.clear(this@MainActivity)
                withContext(Dispatchers.Main) {
                    updateQuestionBankStatus()
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.bank_load_failed, e.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // ---------- 题库状态 ----------

    private fun updateQuestionBankStatus(restoring: Boolean = false) {
        val data = AnswerApplication.excelData
        binding.tvQuestionBankStatus.text = when {
            data != null -> {
                val name = AnswerApplication.currentBankName
                if (name.isNullOrBlank()) getString(R.string.question_bank_loaded, data.pairs.size)
                else getString(R.string.bank_loaded_named, name, data.pairs.size)
            }
            restoring -> getString(R.string.bank_restoring)
            else -> getString(R.string.bank_not_selected)
        }
    }

    // ---------- 启动悬浮窗 ----------

    private fun onStartFloatingClicked() {
        if (AnswerApplication.excelData == null) {
            Toast.makeText(this, R.string.question_bank_not_loaded, Toast.LENGTH_SHORT).show()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }
        requestScreenCapture()
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:$packageName")
        )
        startActivity(intent)
        Toast.makeText(this, R.string.overlay_permission_needed, Toast.LENGTH_LONG).show()
    }

    private fun requestScreenCapture() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as android.media.projection.MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startFloatingWindow() {
        val intent = Intent(this, FloatingWindowService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
    }

    override fun onResume() {
        super.onResume()
        updateQuestionBankStatus()
    }
}
