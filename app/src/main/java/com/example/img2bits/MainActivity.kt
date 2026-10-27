package com.example.img2bits

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    private lateinit var tvSource: TextView
    private lateinit var tvLog: TextView

    private var sourceUri: Uri? = null
    private var pendingSourceUri: Uri? = null
    private var pendingAsBits: Boolean = true
    private var pendingRestoreBytes: ByteArray? = null

    private val pickSourceLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        sourceUri = uri
        val name = uri.lastPathSegment ?: "未知文件"
        tvSource.text = "当前源文件：$name"
        log("已选择源文件：$name")
    }

    private val saveParsedLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { outUri ->
        if (outUri == null) return@registerForActivityResult
        val srcUri = pendingSourceUri ?: return@registerForActivityResult
        try {
            doParse(srcUri, pendingAsBits, outUri)
        } catch (e: Exception) {
            log("解析失败：${e.message}")
            toast("解析失败：${e.message}")
        }
    }

    private val pickTxtLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)?.use {
                BufferedReader(InputStreamReader(it)).readText()
            } ?: return@registerForActivityResult
            analyzeAndAskExt(text)
        } catch (e: Exception) {
            log("读取 txt 失败：${e.message}")
            toast("读取 txt 失败")
        }
    }

    private val saveRestoredLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { outUri ->
        if (outUri == null) return@registerForActivityResult
        val bytes = pendingRestoreBytes ?: return@registerForActivityResult
        try {
            contentResolver.openOutputStream(outUri)?.use { it.write(bytes) }
            log("还原完成，已保存 ${bytes.size} 字节")
            toast("还原完成")
        } catch (e: Exception) {
            log("保存失败：${e.message}")
            toast("保存失败：${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvSource = findViewById(R.id.tvSource)
        tvLog = findViewById(R.id.tvLog)

        findViewById<Button>(R.id.btnSelect).setOnClickListener {
            pickSourceLauncher.launch(arrayOf("*/*"))
        }

        findViewById<Button>(R.id.btnParse).setOnClickListener {
            val uri = sourceUri
            if (uri == null) {
                toast("请先选择源文件")
                return@setOnClickListener
            }
            showParseModeDialog(uri)
        }

        findViewById<Button>(R.id.btnRestore).setOnClickListener {
            pickTxtLauncher.launch(arrayOf("*/*"))
        }
    }

    private fun showParseModeDialog(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("选择解析方式")
            .setMessage(
                "【原始 01 串】\n" +
                    "每个字节拆成 8 位 0/1，体积约为原文件的 8 倍。\n\n" +
                    "【十六进制】\n" +
                    "每个字节写成 2 位十六进制字符，体积约为原文件的 2 倍，更紧凑。\n\n" +
                    "两种方式都可以通过“还原”按钮逐字节恢复原文件。"
            )
            .setPositiveButton("原始 01 串") { _, _ ->
                pendingSourceUri = uri
                pendingAsBits = true
                saveParsedLauncher.launch("bin.txt")
            }
            .setNeutralButton("十六进制") { _, _ ->
                pendingSourceUri = uri
                pendingAsBits = false
                saveParsedLauncher.launch("bin.txt")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doParse(srcUri: Uri, asBits: Boolean, outUri: Uri) {
        contentResolver.openInputStream(srcUri)!!.use { input ->
            contentResolver.openOutputStream(outUri)!!.bufferedWriter().use { writer ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    val sb = StringBuilder(if (asBits) n * 8 else n * 2)
                    for (i in 0 until n) {
                        val b = buf[i].toInt()
                        if (asBits) {
                            for (k in 7 downTo 0) {
                                sb.append(if ((b shr k) and 1 == 1) '1' else '0')
                            }
                        } else {
                            val v = b and 0xFF
                            sb.append(HEX_CHARS[v shr 4])
                            sb.append(HEX_CHARS[v and 0x0F])
                        }
                    }
                    writer.write(sb.toString())
                }
            }
        }
        val mode = if (asBits) "原始 01 串" else "十六进制"
        log("解析完成（$mode）")
        toast("解析完成")
    }

    private fun analyzeAndAskExt(text: String) {
        val compact = text.filterNot { it.isWhitespace() }
        if (compact.isEmpty()) {
            toast("txt 内容为空")
            return
        }
        val allBin = compact.all { it == '0' || it == '1' }
        val allHex = compact.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        val canBin = allBin && compact.length % 8 == 0
        val canHex = allHex && compact.length % 2 == 0

        if (!canBin && !canHex) {
            log("无法识别：既不像 01 串，也不像十六进制")
            toast("无法识别 txt 内容")
            return
        }

        val isBits = canBin
        val detected = if (isBits) "01 串" else "十六进制"
        if (canBin && canHex) {
            log("提示：内容同时符合两种格式，已自动按 01 串还原")
        }

        val density = resources.displayMetrics.density
        val input = EditText(this).apply {
            hint = "例如：png"
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), 0)
        }

        AlertDialog.Builder(this)
            .setTitle("输入还原文件的后缀")
            .setMessage(
                "已识别为：$detected。\n\n" +
                    "请输入后缀（如 png、jpg、pdf，不带点）。\n" +
                    "程序会默认命名为 result.<后缀>。\n\n" +
                    "注意：本程序不做文件头魔数校验，后缀错误可能导致文件打不开。"
            )
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val ext = input.text.toString().trim().trimStart('.').lowercase()
                if (ext.isEmpty() || ext.contains('/') || ext.contains('\\')) {
                    toast("后缀无效")
                    return@setPositiveButton
                }
                try {
                    val bytes = if (isBits) {
                        val n = compact.length / 8
                        val out = ByteArray(n)
                        for (i in 0 until n) {
                            var b = 0
                            for (k in 0..7) {
                                b = (b shl 1) or (compact[i * 8 + k] - '0')
                            }
                            out[i] = b.toByte()
                        }
                        out
                    } else {
                        val n = compact.length / 2
                        val out = ByteArray(n)
                        for (i in 0 until n) {
                            val hi = hexVal(compact[i * 2])
                            val lo = hexVal(compact[i * 2 + 1])
                            out[i] = ((hi shl 4) or lo).toByte()
                        }
                        out
                    }
                    pendingRestoreBytes = bytes
                    saveRestoredLauncher.launch("result.$ext")
                } catch (e: Exception) {
                    log("还原失败：${e.message}")
                    toast("还原失败：${e.message}")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun hexVal(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> 0
    }

    private fun log(msg: String) {
        tvLog.append("$msg\n")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private val HEX_CHARS = "0123456789abcdef".toCharArray()
    }
}
