package app.carry

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.carry.data.CarrySchedule
import app.carry.data.Discover
import app.carry.data.MediaIndex
import app.carry.data.SettingsStore
import app.carry.data.SmbDestination
import app.carry.data.Syncer
import app.carry.data.VaultStore
import app.carry.ui.CarryRoot
import app.carry.ui.CarryTheme
import java.security.Security
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.bouncycastle.jce.provider.BouncyCastleProvider

class CarryApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Android does not ship MD4. SMB authentication still asks for it.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        container = AppContainer(this)
        CarrySchedule.channel(this)
        CarrySchedule.arm(this)
    }
}

class AppContainer(context: android.content.Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    val settings = SettingsStore(context, scope)
    val vault = VaultStore(context)
    val smb = SmbDestination()
    val media = MediaIndex(context)
    val discover = Discover()
    val syncer = Syncer(context, settings, vault, media, smb)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0xFF07111C.toInt()),
            navigationBarStyle = SystemBarStyle.dark(0xFF07111C.toInt()),
        )
        val app = application as CarryApp
        setContent {
            CarryTheme { CarryRoot(app.container) }
        }
    }
}
