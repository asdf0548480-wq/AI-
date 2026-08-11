package com.example.voicedial

import android.app.*
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.view.KeyEvent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.File

class BluetoothMonitorService : Service() {

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var isListening = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED,
                AudioManager.ACTION_HEADSET_PLUG -> {
                    Log.d(TAG, "אוזניות התחברו - מפעיל האזנה")
                    startListening()
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    Log.d(TAG, "אוזניות התנתקו - עוצר האזנה")
                    stopListening()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification("ממתין לחיבור אוזניות..."))

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(AudioManager.ACTION_HEADSET_PLUG)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(bluetoothReceiver, filter)
        }

        loadModel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_FORCE_LISTEN) {
            Log.d(TAG, "הפעלה ידנית (כפתור בדיקה) - מתעלם מבדיקת בלוטות'")
            startListening()
        }
        return START_STICKY
    }

    private fun loadModel() {
        StorageService.unpack(this, "model-he", "model",
            { unpackedModel ->
                model = unpackedModel
                Log.d(TAG, "מודל הזיהוי נטען בהצלחה - offline, ללא רשת")
                showToast("מודל הזיהוי נטען בהצלחה")
            },
            { exception ->
                Log.e(TAG, "שגיאה בטעינת המודל", exception)
                showToast("שגיאה בטעינת המודל: ${exception.javaClass.simpleName}: ${exception.message}")
            })
    }

    private fun startListening() {
        if (isListening) {
            showToast("כבר מאזין")
            return
        }
        val loadedModel = model ?: run {
            Log.e(TAG, "המודל עדיין לא נטען, לא ניתן להאזין")
            showToast("המודל עדיין לא נטען - נסה שוב בעוד כמה שניות")
            return
        }

        try {
            val recognizer = Recognizer(loadedModel, 16000.0f, PhraseConfig.buildGrammarJson())
            speechService = SpeechService(recognizer, 16000.0f)
            speechService?.startListening(object : RecognitionListener {

                override fun onPartialResult(hypothesis: String?) { }

                override fun onResult(hypothesis: String?) {
                    hypothesis ?: return
                    handleRecognizedText(hypothesis)
                }

                override fun onFinalResult(hypothesis: String?) {
                    hypothesis ?: return
                    handleRecognizedText(hypothesis)
                }

                override fun onError(exception: Exception?) {
                    Log.e(TAG, "שגיאת זיהוי: ${exception?.message}")
                    showToast("שגיאת זיהוי: ${exception?.message}")
                }

                override fun onTimeout() { }
            })
            isListening = true
            showToast("מאזין עכשיו! נסה לומר play music")
            updateNotification("מאזין לפקודות קוליות...")
        } catch (e: Exception) {
            Log.e(TAG, "שגיאה בהפעלת ההאזנה: ${e.message}")
            showToast("שגיאה בהפעלת ההאזנה: ${e.message}")
        }
    }

    private fun stopListening() {
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
        isListening = false
        updateNotification("ממתין לחיבור אוזניות...")
    }

    private fun handleRecognizedText(jsonResult: String) {
        val text = try {
            JSONObject(jsonResult).optString("text", "")
        } catch (e: Exception) {
            ""
        }
        if (text.isBlank()) return

        Log.d(TAG, "זוהה: $text")

        val match = PhraseConfig.COMMANDS.firstOrNull { cmd ->
            text.contains(cmd.phrase)
        }

        match?.let { command ->
            Log.d(TAG, "התאמה נמצאה: ${command.phrase} -> ${command.action}")
            showToast("זוהתה פקודה: ${command.phrase}")
            performMediaAction(command.action)
        }
    }

    private fun performMediaAction(action: PhraseConfig.MediaAction) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        when (action) {
            PhraseConfig.MediaAction.PLAY -> sendMediaKeyEvent(audioManager, KeyEvent.KEYCODE_MEDIA_PLAY)
            PhraseConfig.MediaAction.PAUSE -> sendMediaKeyEvent(audioManager, KeyEvent.KEYCODE_MEDIA_PAUSE)
            PhraseConfig.MediaAction.NEXT -> sendMediaKeyEvent(audioManager, KeyEvent.KEYCODE_MEDIA_NEXT)
            PhraseConfig.MediaAction.PREVIOUS -> sendMediaKeyEvent(audioManager, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            PhraseConfig.MediaAction.VOLUME_UP -> audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI
            )
            PhraseConfig.MediaAction.VOLUME_DOWN -> audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI
            )
        }
    }

    private fun sendMediaKeyEvent(audioManager: AudioManager, keyCode: Int) {
        val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        audioManager.dispatchMediaKeyEvent(downEvent)
        audioManager.dispatchMediaKeyEvent(upEvent)
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "שליטה קולית", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("שליטה קולית פעילה")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        unregisterReceiver(bluetoothReceiver)
    }

    companion object {
        private const val TAG = "BluetoothMonitorSvc"
        private const val CHANNEL_ID = "voice_dial_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_FORCE_LISTEN = "com.example.voicedial.FORCE_LISTEN"
    }
}
