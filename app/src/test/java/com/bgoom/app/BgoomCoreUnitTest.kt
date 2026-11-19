package com.bgoom.app

import com.bgoom.app.data.OcrOutputFormat
import com.bgoom.app.idphoto.IdPhotoCatalog
import com.bgoom.app.ocr.LocalPpOcrEngine
import com.bgoom.app.ocr.LocalTexoFormulaEngine
import com.bgoom.app.ocr.OcrFormatConverter
import com.bgoom.app.ocr.OcrLineBox
import com.bgoom.app.ssh.SshKeyManager
import com.bgoom.app.typst.NativeTypstEngine
import com.bgoom.app.update.GitHubUpdater
import com.bgoom.app.util.AndroidFileHelper
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.Security

class BgoomCoreUnitTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun initCrypto() {
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    @Test
    fun ed25519KeypairGeneratesAndValidatesViaSshj() {
        val kp = SshKeyManager.generateEd25519KeyPair("test@bgoom")
        assertTrue(kp.privateKeyOpenSsh.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
        assertTrue(kp.publicKeyAuthorizedLine.startsWith("ssh-ed25519 "))
        assertTrue(kp.fingerprintSha256.startsWith("SHA256:"))

        val validated = SshKeyManager.validatePrivateKey(kp.privateKeyOpenSsh, null)
        assertTrue("Generated key must parse cleanly via SSHJ", validated.isSuccess)
        assertEquals(kp.fingerprintSha256, validated.getOrThrow().fingerprintSha256)
    }

    @Test
    fun sshKeyValidatorRejectsPlainPasswordsAndBlankInput() {
        assertTrue(SshKeyManager.validatePrivateKey("", null).isFailure)
        assertTrue(SshKeyManager.validatePrivateKey("my-secret-password-123", null).isFailure)
    }

    @Test
    fun texoDetokenizerNormalizesRealIntegralTokens() {
        val rawTokens = listOf(
            "\\int", "_", "{", "0", "}", "^", "{", "\\infin", "}",
            "e", "^", "{", "-", "x", "^", "{", "2", "}", "}", "~",
            "\\mathrm", "{", "d", "}", "x", "=", "{", "\\frac", "{", "\\sqrt", "{", "\\pi", "}", "}", "{", "2", "}", "}"
        )
        val latex = LocalTexoFormulaEngine.detokenizeLatex(rawTokens)
        assertTrue("Must normalize \\infin -> \\infty", latex.contains("\\infty"))
        assertFalse("Must not contain \\infin", latex.contains("\\infin"))
        assertTrue(latex.contains("\\frac"))
        assertTrue(latex.contains("\\sqrt{\\pi}"))
    }

    @Test
    fun ctcDecoderCollapsesBlanksAndRepeats() {
        val dict = listOf("<blank>", "大", "爆", "A", " ")
        // Steps: 0, 1, 1, 0, 2, 3, 3, 0 -> "大爆A"
        val steps = arrayOf(
            floatArrayOf(0.9f, 0.05f, 0.02f, 0.02f, 0.01f),
            floatArrayOf(0.05f, 0.9f, 0.02f, 0.02f, 0.01f),
            floatArrayOf(0.05f, 0.85f, 0.04f, 0.03f, 0.03f),
            floatArrayOf(0.95f, 0.01f, 0.01f, 0.02f, 0.01f),
            floatArrayOf(0.02f, 0.02f, 0.92f, 0.02f, 0.02f),
            floatArrayOf(0.01f, 0.01f, 0.02f, 0.94f, 0.02f),
            floatArrayOf(0.01f, 0.01f, 0.02f, 0.91f, 0.05f),
            floatArrayOf(0.98f, 0.005f, 0.005f, 0.005f, 0.005f),
        )
        val (text, conf) = LocalPpOcrEngine.decodeCtc(steps, dict)
        assertEquals("大爆A", text)
        assertTrue(conf > 0.9f)
    }

    @Test
    fun ocrFormatConverterSortsReadingOrderAndStructuresTypst() {
        val lines = listOf(
            OcrLineBox("第二段正文内容。", 0.96f, 20f, 180f, 300f, 202f),
            OcrLineBox("大爆测试标题", 0.99f, 20f, 10f, 260f, 54f),
            OcrLineBox("第一段第一行", 0.95f, 20f, 90f, 200f, 112f),
            OcrLineBox("接续内容", 0.94f, 210f, 91f, 340f, 113f),
        )
        val typst = OcrFormatConverter.assembleLines(lines, OcrOutputFormat.TYPST)
        assertTrue(typst.startsWith("= 大爆测试标题"))
        assertTrue(typst.contains("第一段第一行接续内容"))
        assertTrue(typst.contains("第二段正文内容。"))

        val md = OcrFormatConverter.assembleLines(lines, OcrOutputFormat.MARKDOWN)
        assertTrue(md.startsWith("## 大爆测试标题"))
    }

    @Test
    fun idPhotoSpecsAndTypstCjkHeaderWork() {
        val oneInch = IdPhotoCatalog.specs.first { it.id == "1inch" }
        assertEquals(295, oneInch.widthPx)
        assertEquals(413, oneInch.heightPx)

        val wrapped = NativeTypstEngine.ensureCjkFallbackHeader("= 你好")
        assertTrue(wrapped.contains("Noto Sans CJK SC"))
        val custom = "#set text(font: \"Songti SC\")\n= 你好"
        assertEquals(custom, NativeTypstEngine.ensureCjkFallbackHeader(custom))
    }

    @Test
    fun githubVersionComparatorAndMimeTypeDetectionWork() {
        assertTrue(GitHubUpdater.isNewerVersion("v1.2.0", "1.1.0"))
        assertTrue(GitHubUpdater.isNewerVersion("2.0.0", "1.9.9"))
        assertFalse(GitHubUpdater.isNewerVersion("1.1.0", "1.1.0"))
        assertFalse(GitHubUpdater.isNewerVersion("1.0.9", "1.1.0"))

        assertEquals("application/pdf", AndroidFileHelper.guessMimeType("paper.PDF"))
        assertEquals("image/jpeg", AndroidFileHelper.guessMimeType("id_photo.jpg"))
        assertEquals("text/plain", AndroidFileHelper.guessMimeType("notes.typ"))
    }
}
