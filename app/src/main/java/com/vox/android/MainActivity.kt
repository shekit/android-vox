package com.vox.android

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "VoxMain"
    }

    private lateinit var editCommand: EditText
    private lateinit var buttonSend: Button
    private lateinit var buttonCapture: Button
    private lateinit var buttonLaunchApp: Button
    private lateinit var textResponse: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity onCreate")
        setContentView(R.layout.activity_main)

        editCommand = findViewById(R.id.editCommand)
        buttonSend = findViewById(R.id.buttonSend)
        buttonCapture = findViewById(R.id.buttonCapture)
        buttonLaunchApp = findViewById(R.id.buttonLaunchApp)
        textResponse = findViewById(R.id.textResponse)

        // P5.7: Action input in test UI
        buttonSend.setOnClickListener {
            val command = editCommand.text.toString().trim()
            Log.d(TAG, "Send button clicked with command: $command")

            val service = VoxAccessibilityService.getInstance()
            if (service == null) {
                textResponse.text = "Accessibility service not running. Please enable it in Settings."
                editCommand.text.clear()
                return@setOnClickListener
            }

            // Parse and execute commands
            val result = when {
                command.equals("back", ignoreCase = true) -> {
                    val success = service.pressBack()
                    if (success) "Pressed back button" else "Back action failed"
                }
                command.equals("home", ignoreCase = true) -> {
                    val success = service.pressHome()
                    if (success) "Pressed home button" else "Home action failed"
                }
                command.startsWith("tap ", ignoreCase = true) -> {
                    val text = command.substring(4).trim()
                    val success = service.tapByText(text)
                    if (success) "Tapped '$text'" else "Could not find/tap '$text'"
                }
                command.startsWith("type ", ignoreCase = true) -> {
                    val parts = command.substring(5).split(" into ", limit = 2)
                    if (parts.size == 2) {
                        val textToType = parts[0].trim()
                        val nodeText = parts[1].trim()
                        val success = service.typeTextByText(nodeText, textToType)
                        if (success) "Typed '$textToType' into '$nodeText'" else "Could not type into '$nodeText'"
                    } else {
                        "Usage: type <text> into <field>"
                    }
                }
                command.startsWith("launch ", ignoreCase = true) -> {
                    val appName = command.substring(7).trim()
                    val packageName = when (appName.lowercase()) {
                        "settings" -> "com.android.settings"
                        "messages" -> "com.google.android.apps.messaging"
                        "chrome" -> "com.android.chrome"
                        else -> appName // Assume it's a package name
                    }
                    val success = service.launchApp(packageName)
                    if (success) "Launched $appName" else "Could not launch $appName"
                }
                command.equals("scroll down", ignoreCase = true) || command.equals("scroll", ignoreCase = true) -> {
                    val success = service.scrollForwardInActiveWindow()
                    if (success) "Scrolled down" else "Could not scroll"
                }
                else -> {
                    "Unknown command. Try: tap <text>, launch <app>, back, home, scroll down"
                }
            }

            textResponse.text = result
            editCommand.text.clear()
        }

        // P4.6: Capture button triggers tree fetch
        buttonCapture.setOnClickListener {
            Log.d(TAG, "Capture button clicked")
            val treeJson = VoxAccessibilityService.getLatestTree()
            if (treeJson.isNotEmpty()) {
                // P4.5: Display tree in UI
                textResponse.text = treeJson
                Log.d(TAG, "Displayed tree JSON (${treeJson.length} chars)")
            } else {
                textResponse.text = "No UI tree available yet. Open another app and come back."
                Log.w(TAG, "No tree data available")
            }
        }

        // P5.1: Launch app by package name
        buttonLaunchApp.setOnClickListener {
            Log.d(TAG, "Launch app button clicked")
            val service = VoxAccessibilityService.getInstance()
            if (service != null) {
                val packageName = "com.android.settings"
                val success = service.launchApp(packageName)
                textResponse.text = if (success) {
                    "Launched Settings app (package: $packageName)"
                } else {
                    "Failed to launch Settings app"
                }

                // P5.2, P5.3, P5.5: Test finding, tapping, and scrolling after launching Settings
                if (success) {
                    android.os.Handler(mainLooper).postDelayed({
                        // P5.3: Tap Wi-Fi
                        val tapSuccess = service.tapByText("Wi")
                        if (tapSuccess) {
                            textResponse.text = "Tapped 'Wi' (Wi-Fi) setting"

                            // P5.5: Wait then scroll
                            android.os.Handler(mainLooper).postDelayed({
                                val scrollSuccess = service.scrollForwardInActiveWindow()
                                textResponse.text = if (scrollSuccess) {
                                    "Tapped Wi-Fi, then scrolled down"
                                } else {
                                    "Tapped Wi-Fi but couldn't scroll"
                                }
                            }, 1500)
                        } else {
                            textResponse.text = "Launched Settings but couldn't find/tap 'Wi' node"
                        }
                    }, 1000) // Wait 1 second for Settings to load
                }
            } else {
                textResponse.text = "Accessibility service not running. Please enable it in Settings."
                Log.e(TAG, "Accessibility service not available")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "MainActivity onResume")
    }
}
