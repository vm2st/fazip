package com.vm2st.fazip

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.vm2st.fazip.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.lingala.zip4j.io.inputstream.ZipInputStream
import java.io.BufferedOutputStream
import java.io.FilterInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var zipUri: Uri? = null
    private var destUri: Uri? = null
    private var extractedReadmeUri: Uri? = null
    private var extractionJob: Job? = null

    private val selectZipLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            zipUri = result.data?.data
            binding.tvZipPath.text = zipUri?.path ?: getString(R.string.tv_zip_not_selected)
            resetReadmeState()
            checkReadyToExtract()
        }
    }

    private val selectDestLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            destUri = result.data?.data
            destUri?.let {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                binding.tvDestPath.text = it.path ?: getString(R.string.tv_dest_not_selected)
                resetReadmeState()
                checkReadyToExtract()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val purpleColor = android.graphics.Color.parseColor("#6200EE")

        if (android.os.Build.VERSION.SDK_INT >= 35) {
            // 1. Для Android 15/16 (API 35/36): Создаем искусственный фон под прозрачный статус-бар
            val statusBarBg = View(this).apply {
                setBackgroundColor(purpleColor)
            }
            (window.decorView as android.view.ViewGroup).addView(
                statusBarBg,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                0 // Высоту определим динамически чуть ниже
            )

            // 2. Слушаем системные инсеты, чтобы узнать точную высоту статус-бара (с учетом челок/вырезов)
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
                val statusBarInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())

                // Подгоняем высоту фиолетовой плашки под системный статус-бар
                statusBarBg.layoutParams.height = statusBarInsets.top
                statusBarBg.requestLayout()

                // Мягко смещаем контент вниз, сохраняя исходные боковые отступы в 24dp
                val density = resources.displayMetrics.density
                val basePadding = (24 * density).toInt()
                view.setPadding(basePadding, statusBarInsets.top + basePadding, basePadding, basePadding)

                insets
            }
        } else {
            // Для Android 12/14 (API < 35) старый проверенный способ
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            window.statusBarColor = purpleColor
        }

        // Системные иконки (время, батарея) делаем строго БЕЛЫМИ на всех версиях Android
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
        }

        binding.btnSelectZip.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
            }
            selectZipLauncher.launch(intent)
        }

        binding.btnSelectDest.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            selectDestLauncher.launch(intent)
        }

        binding.btnExtract.setOnClickListener {
            if (zipUri != null && destUri != null) {
                initiateExtraction()
            }
        }

        binding.btnStop.setOnClickListener {
            extractionJob?.cancel()
        }

        binding.btnOpenReadme.setOnClickListener {
            extractedReadmeUri?.let { uri ->
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "text/markdown")
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                startActivity(Intent.createChooser(intent, getString(R.string.btn_readme_found)))
            }
        }
    }

    private fun checkReadyToExtract() {
        binding.btnExtract.isEnabled = zipUri != null && destUri != null
    }

    private fun resetReadmeState() {
        extractedReadmeUri = null
        binding.btnOpenReadme.text = getString(R.string.btn_readme_missing)
        binding.btnOpenReadme.isEnabled = false
    }

    private fun setUiState(isExtracting: Boolean) {
        binding.btnSelectZip.isEnabled = !isExtracting
        binding.btnSelectDest.isEnabled = !isExtracting
        binding.btnExtract.isEnabled = !isExtracting && zipUri != null && destUri != null
        binding.progressContainer.visibility = if (isExtracting) View.VISIBLE else View.GONE
        if (!isExtracting) {
            binding.progressBar.progress = 0
            binding.tvProgressPercent.text = getString(R.string.progress_percent_format, 0)
            binding.tvCurrentFileName.text = ""
        }
    }

    private fun initiateExtraction() {
        lifecycleScope.launch(Dispatchers.Main) {
            setUiState(isExtracting = true)
            resetReadmeState()

            val isEncrypted = checkIsEncrypted(zipUri!!)

            if (!isActive) {
                setUiState(isExtracting = false)
                return@launch
            }

            if (isEncrypted) {
                binding.progressContainer.visibility = View.GONE
                showPasswordDialog()
            } else {
                extractZip(null)
            }
        }
    }

    private suspend fun checkIsEncrypted(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        var encrypted = false
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zipStream ->
                    val header = zipStream.nextEntry
                    if (header != null && header.isEncrypted) {
                        encrypted = true
                    }
                }
            }
        } catch (e: Exception) {
            if (e.message?.contains("password", ignoreCase = true) == true ||
                e.message?.contains("decryption", ignoreCase = true) == true) {
                encrypted = true
            } else {
                e.printStackTrace()
            }
        }
        encrypted
    }

    private fun showPasswordDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.dialog_hint_password)
        }

        val hideKeyboard = {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(input.windowToken, 0)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_encrypted_title))
            .setMessage(getString(R.string.dialog_encrypted_msg))
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.dialog_ok)) { _, _ ->
                val password = input.text.toString().toCharArray()
                hideKeyboard()
                extractZip(password)
            }
            .setNegativeButton(getString(R.string.dialog_cancel)) { dialog, _ ->
                hideKeyboard()
                setUiState(isExtracting = false)
                dialog.cancel()
            }
            .show()
    }

    private fun getUriSize(uri: Uri): Long {
        return try {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val size = pfd.statSize
                if (size > 0) size else 1L
            } ?: 1L
        } catch (_: Exception) {
            1L
        }
    }

    private fun extractZip(password: CharArray?) {
        setUiState(isExtracting = true)
        var currentFileDoc: DocumentFile? = null

        extractionJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val totalZipSize = getUriSize(zipUri!!)
                var totalBytesRead = 0L
                var lastPercent = 0

                val rootDestDir = DocumentFile.fromTreeUri(this@MainActivity, destUri!!)
                    ?: throw Exception(getString(R.string.err_no_dest_access))

                val baseInputStream = contentResolver.openInputStream(zipUri!!)
                    ?: throw Exception("Failed to open stream")

                // ИСПРАВЛЕНО: Убран избыточный qualifier у FilterInputStream
                val countingStream = object : FilterInputStream(baseInputStream) {
                    private fun updateProgress(n: Long) {
                        if (n > 0) {
                            totalBytesRead += n
                            val percent = ((totalBytesRead * 100) / totalZipSize).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                lifecycleScope.launch(Dispatchers.Main) {
                                    binding.progressBar.progress = percent
                                    // ИСПРАВЛЕНО: Заменено на строковый ресурс с плейсхолдером
                                    binding.tvProgressPercent.text = getString(R.string.progress_percent_format, percent)
                                }
                            }
                        }
                    }
                    override fun read(): Int {
                        val b = super.read()
                        if (b != -1) updateProgress(1L)
                        return b
                    }
                    override fun read(b: ByteArray?): Int {
                        val n = super.read(b)
                        updateProgress(n.toLong())
                        return n
                    }
                    override fun read(b: ByteArray?, off: Int, len: Int): Int {
                        val n = super.read(b, off, len)
                        updateProgress(n.toLong())
                        return n
                    }
                    override fun skip(n: Long): Long {
                        val skipped = super.skip(n)
                        updateProgress(skipped)
                        return skipped
                    }
                }

                val zipStream = if (password != null) {
                    ZipInputStream(countingStream, password)
                } else {
                    ZipInputStream(countingStream)
                }

                zipStream.use { stream ->
                    var entry = stream.nextEntry
                    val buffer = ByteArray(8192)

                    while (entry != null) {
                        if (!isActive) throw CancellationException()

                        val fileName = entry.fileName

                        if (fileName.contains("../") || fileName.contains("..\\")) {
                            throw SecurityException(getString(R.string.err_zip_slip, fileName))
                        }

                        if (entry.isDirectory) {
                            getOrCreateSubFolder(rootDestDir, fileName)
                        } else {
                            val pureFileName = fileName.substringAfterLast('/')

                            withContext(Dispatchers.Main) {
                                // ИСПРАВЛЕНО: Заменено на строковый ресурс с плейсхолдером
                                binding.tvCurrentFileName.text = getString(R.string.current_file_format, pureFileName)
                            }

                            val fileDoc = createFileViaSAF(rootDestDir, fileName)
                            currentFileDoc = fileDoc

                            contentResolver.openOutputStream(fileDoc.uri)?.use { outputStream ->
                                BufferedOutputStream(outputStream).use { bos ->
                                    var count: Int
                                    while (stream.read(buffer).also { count = it } != -1) {
                                        if (!isActive) throw CancellationException()
                                        bos.write(buffer, 0, count)
                                    }
                                }
                            }
                            currentFileDoc = null

                            if (fileName.endsWith("README.md", ignoreCase = true)) {
                                extractedReadmeUri = fileDoc.uri
                            }
                        }
                        entry = stream.nextEntry
                    }
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_success), Toast.LENGTH_LONG).show()
                    if (extractedReadmeUri != null) {
                        binding.btnOpenReadme.text = getString(R.string.btn_readme_found)
                        binding.btnOpenReadme.isEnabled = true
                    }
                }
            } catch (_: CancellationException) { // ИСПРАВЛЕНО: Неиспользуемый параметр изменен на "_"
                withContext(NonCancellable) {
                    currentFileDoc?.delete()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, getString(R.string.toast_cancelled), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val msg = if (e.message?.contains("Wrong Password", ignoreCase = true) == true) {
                        getString(R.string.toast_wrong_password)
                    } else {
                        getString(R.string.toast_error_prefix, e.message)
                    }
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(NonCancellable) {
                    withContext(Dispatchers.Main) {
                        setUiState(isExtracting = false)
                    }
                }
            }
        }
    }

    private fun getOrCreateSubFolder(rootDir: DocumentFile, relativePath: String): DocumentFile {
        var currentDir = rootDir
        val parts = relativePath.trimEnd('/').split("/")

        for (part in parts) {
            if (part.isEmpty()) continue
            val nextDir = currentDir.findFile(part)
            currentDir = nextDir ?: currentDir.createDirectory(part)
                    ?: throw Exception(getString(R.string.err_create_dir, part))
        }
        return currentDir
    }

    private fun createFileViaSAF(rootDir: DocumentFile, relativePath: String): DocumentFile {
        val lastSlashIndex = relativePath.lastIndexOf('/')
        val dir = if (lastSlashIndex != -1) {
            val dirPath = relativePath.substring(0, lastSlashIndex)
            getOrCreateSubFolder(rootDir, dirPath)
        } else {
            rootDir
        }

        val fileName = relativePath.substring(lastSlashIndex + 1)
        dir.findFile(fileName)?.delete()

        return dir.createFile("application/octet-stream", fileName)
            ?: throw Exception(getString(R.string.err_create_file, fileName))
    }
}