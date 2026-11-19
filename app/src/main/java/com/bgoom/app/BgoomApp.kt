package com.bgoom.app

import android.app.Application
import com.bgoom.app.data.BgoomDatabase
import com.bgoom.app.data.DeviceStore
import com.bgoom.app.idphoto.PortraitMattingEngine
import com.bgoom.app.ocr.LocalPpOcrEngine
import com.bgoom.app.ocr.LocalTexoFormulaEngine
import com.bgoom.app.ocr.RemoteVlmOcrClient
import com.bgoom.app.ssh.SshHubRepository
import com.bgoom.app.ssh.SshSessionPool
import com.bgoom.app.typst.TypstCompilerService
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class BgoomApp : Application() {

    lateinit var deviceStore: DeviceStore
        private set
    lateinit var database: BgoomDatabase
        private set
    lateinit var sshPool: SshSessionPool
        private set
    lateinit var sshRepo: SshHubRepository
        private set
    lateinit var mattingEngine: PortraitMattingEngine
        private set
    lateinit var localOcrEngine: LocalPpOcrEngine
        private set
    lateinit var localFormulaEngine: LocalTexoFormulaEngine
        private set
    lateinit var remoteVlmClient: RemoteVlmOcrClient
        private set
    lateinit var typstCompiler: TypstCompilerService
        private set

    override fun onCreate() {
        super.onCreate()
        // Replace Android's stripped built-in BC provider with full BouncyCastle so SSHJ Ed25519/OpenSSH keys work reliably
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)

        deviceStore = DeviceStore(this)
        database = BgoomDatabase(this)
        sshPool = SshSessionPool(deviceStore)
        sshRepo = SshHubRepository(sshPool, database)
        mattingEngine = PortraitMattingEngine(this)
        localOcrEngine = LocalPpOcrEngine(this)
        localFormulaEngine = LocalTexoFormulaEngine(this)
        remoteVlmClient = RemoteVlmOcrClient()
        typstCompiler = TypstCompilerService(this, sshRepo)

        // Auto-resume background persistence if user previously enabled it
        if (deviceStore.settings.value.keepAliveReceiveEnabled && deviceStore.getActiveDevice() != null) {
            runCatching { com.bgoom.app.ssh.BgoomSyncService.start(this) }
        }
    }

    override fun onTerminate() {
        sshPool.disconnectAll()
        super.onTerminate()
    }
}
