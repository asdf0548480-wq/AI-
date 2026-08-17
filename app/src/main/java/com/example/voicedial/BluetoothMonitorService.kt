package com.example.voicedial

import android.app.*
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.provider.MediaStore
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
    private var isSongSearchMode = false
    private var mediaPlayer: MediaPlayer? = null
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

        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "אין הרשאת מיקרופון - לא ניתן להפעיל שירות מסוג microphone")
            showToast("חסרה הרשאת מיקרופון - פתח את האפליקציה ואשר הרשאות")
            stopSelf()
            return
        }

        try {
            startForeground(NOTIFICATION_ID, buildNotification("ממתין לחיבור אוזניות..."))
        } catch (e: Exception) {
            Log.e(TAG, "קריסה בהפעלת startForeground", e)
            showToast("שגיאה בהפעלת השירות: ${e.javaClass.simpleName}: ${e.message}")
            stopSelf()
            return
        }

        try {
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
        } catch (e: Exception) {
            Log.e(TAG, "קריסה בהמשך onCreate", e)
            showToast("שגיאה באתחול השירות: ${e.javaClass.simpleName}: ${e.message}")
            updateNotification("שגיאה באתחול: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_FORCE_LISTEN) {
            Log.d(TAG, "הפעלה ידנית (כפתור בדיקה) - מתעלם מבדיקת בלוטות'")
            startListening()
        }
        return START_STICKY
    }

    private fun loadModel() {
        updateNotification("טוען מודל זיהוי דיבור...")
        StorageService.unpack(this, "model-he", "model",
            { unpackedModel ->
                model = unpackedModel
                Log.d(TAG, "מודל הזיהוי נטען בהצלחה - offline, ללא רשת")
                showToast("מודל הזיהוי נטען בהצלחה")
                updateNotification("מודל נטען בהצלחה - ממתין לחיבור אוזניות...")
            },
            { exception ->
                Log.e(TAG, "שגיאה בטעינת המודל", exception)
                val detail = "${exception.javaClass.simpleName}: ${exception.message}"
                showToast("שגיאה בטעינת המודל: $detail")
                updateNotification("שגיאה בטעינת המודל: $detail")
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

        if (isSongSearchMode) {
            isSongSearchMode = false
            showToast("מחפש: $text")
            searchAndPlaySong(text)
            stopListening()
            startListening()
            return
        }

        val match = PhraseConfig.COMMANDS.firstOrNull { cmd ->
            text.contains(cmd.phrase)
        }

        match?.let { command ->
            Log.d(TAG, "התאמה נמצאה: ${command.phrase} -> ${command.action}")
            showToast("זוהתה פקודה: ${command.phrase}")
            performMediaAction(command.action)
        }
    }

    private fun startSongNameCapture() {
        val loadedModel = model ?: run {
            showToast("המודל עדיין לא נטען")
            return
        }
        stopListening()
        try {
            val recognizer = Recognizer(loadedModel, 16000.0f)
            speechService = SpeechService(recognizer, 16000.0f)
            isSongSearchMode = true
            speechService?.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {}
                override fun onResult(hypothesis: String?) {
                    hypothesis ?: return
                    handleRecognizedText(hypothesis)
                }
                override fun onFinalResult(hypothesis: String?) {
                    hypothesis ?: return
                    handleRecognizedText(hypothesis)
                }
                override fun onError(exception: Exception?) {
                    Log.e(TAG, "שגיאת זיהוי שם שיר: ${exception?.message}")
                    showToast("שגיאת זיהוי: ${exception?.message}")
                    isSongSearchMode = false
                    startListening()
                }
                override fun onTimeout() {
                    isSongSearchMode = false
                    startListening()
                }
            })
            isListening = true
            showToast("איזה שיר? תגיד את השם עכשיו...")
            updateNotification("מקשיב לשם השיר...")
        } catch (e: Exception) {
            Log.e(TAG, "שגיאה בהפעלת זיהוי שם שיר: ${e.message}")
            showToast("שגיאה: ${e.message}")
            isSongSearchMode = false
            startListening()
        }
    }

    private fun searchAndPlaySong(query: String) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, readAudioPermission()
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            showToast("חסרה הרשאת גישה לקבצי מוזיקה")
            updateNotification("חסרה הרשאת גישה לקבצי מוזיקה")
            return
        }

        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.DISPLAY_NAME)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        var foundUri: Uri? = null
        var foundTitle: String? = null
        var bestScore = 0.0

        try {
            contentResolver.query(collection, projection, selection, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val title = cursor.getString(titleCol) ?: ""
                    val name = cursor.getString(nameCol) ?: ""

                    val scoreTitle = Transliteration.similarity(query, title)
                    val scoreName = Transliteration.similarity(query, name)
                    val score = maxOf(scoreTitle, scoreName)

                    if (score > bestScore) {
                        bestScore = score
                        val id = cursor.getLong(idCol)
                        foundUri = Uri.withAppendedPath(collection, id.toString())
                        foundTitle = title.ifBlank { name }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "שגיאה בחיפוש שירים", e)
            showToast("שגיאה בחיפוש: ${e.message}")
            return
        }

        if (bestScore < 0.45) {
            foundUri = null
        }

        val uri = foundUri
        if (uri == null) {
            showToast("לא נמצא שיר בשם: $query")
            updateNotification("לא נמצא שיר בשם: $query")
            return
        }

        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, uri)
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setOnPreparedListener { start() }
                prepareAsync()
            }
            showToast("מנגן: $foundTitle")
            updateNotification("מנגן: $foundTitle")
        } catch (e: Exception) {
            Log.e(TAG, "שגיאה בניגון השיר", e)
            showToast("שגיאה בניגון: ${e.message}")
        }
    }

    private fun readAudioPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO
        else android.Manifest.permission.READ_EXTERNAL_STORAGE

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
            PhraseConfig.MediaAction.PLAY_SPECIFIC -> startSongNameCapture()
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
        mediaPlayer?.release()
        mediaPlayer = null
        unregisterReceiver(bluetoothReceiver)
    }

    companion object {
        private const val TAG = "BluetoothMonitorSvc"
        private const val CHANNEL_ID = "voice_dial_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_FORCE_LISTEN = "com.example.voicedial.FORCE_LISTEN"
    }
}
