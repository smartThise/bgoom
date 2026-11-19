package com.bgoom.app.typst

import android.content.Context
import com.bgoom.app.data.SshDevice
import com.bgoom.app.data.TypstCompileMode
import com.bgoom.app.ssh.SshHubRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class TypstCompileResult(
    val pdfFile: File,
    val modeUsed: TypstCompileMode,
    val elapsedMs: Long,
)

class TypstCompilerService(
    private val context: Context,
    private val sshRepo: SshHubRepository,
) {
    suspend fun compile(
        source: String,
        mode: TypstCompileMode,
        activeDevice: SshDevice?,
    ): Result<TypstCompileResult> = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val outDir = File(context.cacheDir, "typst_pdfs").apply { mkdirs() }
        val outFile = File(outDir, "bgoom_typst_${System.currentTimeMillis()}.pdf")

        when (mode) {
            TypstCompileMode.LOCAL_OFFLINE -> runCatching {
                val bytes = NativeTypstEngine.compileToPdf(source)
                FileOutputStream(outFile).use { it.write(bytes) }
                TypstCompileResult(
                    pdfFile = outFile,
                    modeUsed = TypstCompileMode.LOCAL_OFFLINE,
                    elapsedMs = System.currentTimeMillis() - start,
                )
            }

            TypstCompileMode.REMOTE_SSH -> {
                if (activeDevice == null) {
                    return@withContext Result.failure(
                        IllegalStateException("尚未选择 SSH 设备，请先添加/选择设备或切换为「本地离线」编译。")
                    )
                }
                sshRepo.compileTypstRemote(activeDevice, source).map { bytes ->
                    FileOutputStream(outFile).use { it.write(bytes) }
                    TypstCompileResult(
                        pdfFile = outFile,
                        modeUsed = TypstCompileMode.REMOTE_SSH,
                        elapsedMs = System.currentTimeMillis() - start,
                    )
                }
            }
        }
    }
}
