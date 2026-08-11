package com.example.voicedial

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val requiredPermissions = mutableListOf(
        Manifest.permission.RECORD_AUDIO
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val status = TextView(this).apply {
            text = "אפליקציית שליטה קולית במוזיקה\n\n" +
                    "השירות יאזין לפקודות קוליות רק כאשר אוזניות בלוטות' מחוברות.\n" +
                    "כל הזיהוי מתבצע במכשיר עצמו, ללא חיבור לאינטרנט.\n\n" +
                    "6 הפקודות: תפעיל מוזיקה / תפסיק מוזיקה / תעביר שיר קדימה / " +
                    "תעביר שיר אחורה / תגביר קול / תנמיך קול"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(40, 60, 40, 40)
        }
        val startButton = Button(this).apply {
            text = "הפעל שירות רקע"
            setOnClickListener { requestPermissionsAndStart() }
        }

        val testButton = Button(this).apply {
            text = "בדיקה: התחל להאזין עכשיו (בלי אוזניות)"
            setOnClickListener { requestPermissionsAndTest() }
        }

        val backgroundImage = ImageView(this).apply {
            setImageResource(R.drawable.background_photo)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val darkOverlay = android.view.View(this).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val contentColumn = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_VERTICAL }
            addView(status)
            addView(startButton)
            addView(testButton)
        }

        val root = FrameLayout(this).apply {
            addView(backgroundImage)
            addView(darkOverlay)
            addView(contentColumn)
        }
        setContentView(root)
    }

    private var pendingAction: String = ACTION_NONE

    private fun requestPermissionsAndTest() {
        pendingAction = ACTION_TEST
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            Toast.makeText(this, "מבקש הרשאות...", Toast.LENGTH_SHORT).show()
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        } else {
            forceStartListening()
        }
    }

    private fun forceStartListening() {
        Toast.makeText(this, "מפעיל האזנה לבדיקה...", Toast.LENGTH_SHORT).show()
        val serviceIntent = Intent(this, BluetoothMonitorService::class.java).apply {
            action = BluetoothMonitorService.ACTION_FORCE_LISTEN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun requestPermissionsAndStart() {
        pendingAction = ACTION_START
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            Toast.makeText(this, "מבקש הרשאות...", Toast.LENGTH_SHORT).show()
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        } else {
            startService()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            Toast.makeText(this, "הרשאות אושרו", Toast.LENGTH_SHORT).show()
            when (pendingAction) {
                ACTION_TEST -> forceStartListening()
                ACTION_START -> startService()
            }
        } else {
            Toast.makeText(this, "ההרשאות לא אושרו - נדרש מיקרופון כדי שזה יעבוד", Toast.LENGTH_LONG).show()
        }
    }

    private fun startService() {
        Toast.makeText(this, "מפעיל שירות רקע...", Toast.LENGTH_SHORT).show()
        val serviceIntent = Intent(this, BluetoothMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    companion object {
        private const val ACTION_NONE = "none"
        private const val ACTION_TEST = "test"
        private const val ACTION_START = "start"
    }
}
