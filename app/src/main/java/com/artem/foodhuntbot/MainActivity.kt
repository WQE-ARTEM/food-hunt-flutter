package com.artem.foodhuntbot

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "Food Hunt Авто-Бот\n\nДля работы поверх игры включи 2 разрешения:"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        }

        val btnOverlay = Button(this).apply {
            text = "1. Разрешить окно поверх игр"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
        }

        val btnAccess = Button(this).apply {
            text = "2. Включить 'Food Hunt Bot' в Спец. возможностях"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val info = TextView(this).apply {
            text = "\nКак только включишь оба пункта — на экране появится плавающая кнопка [▶ СТАРТ]. Открой игру и нажми её!"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 48, 0, 0)
        }

        layout.addView(title)
        layout.addView(btnOverlay)
        layout.addView(btnAccess)
        layout.addView(info)

        setContentView(layout)
    }
}
