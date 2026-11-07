package ma.jomhor.scanner

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.view.WindowManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    // Scan feedback (docs/02 step 8): long beep when admitted, error tone when refused. Alarm stream, full
    // volume: a gate is loud and the ringer may be silenced. No audio plugin or asset needed.
    private var tone: ToneGenerator? = null

    private fun tone(): ToneGenerator =
        tone ?: ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME).also { tone = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots, screen recording or recent-apps thumbnail of the gate screens (plan lot-2 §6).
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onDestroy() {
        // ToneGenerator holds a native audio track until released.
        tone?.release()
        tone = null
        super.onDestroy()
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, DeviceKey.CHANNEL).setMethodCallHandler(DeviceKey())
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "ma.jomhor.scanner/tone").setMethodCallHandler { call, result ->
            when (call.method) {
                "ok" -> tone().startTone(ToneGenerator.TONE_DTMF_1, 500)
                "refused" -> tone().startTone(ToneGenerator.TONE_SUP_ERROR, 600)
                else -> return@setMethodCallHandler result.notImplemented()
            }
            result.success(null)
        }
    }
}
