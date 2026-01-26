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
    private lateinit var textResponse: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity onCreate")
        setContentView(R.layout.activity_main)

        editCommand = findViewById(R.id.editCommand)
        buttonSend = findViewById(R.id.buttonSend)
        textResponse = findViewById(R.id.textResponse)

        buttonSend.setOnClickListener {
            val command = editCommand.text.toString()
            Log.d(TAG, "Send button clicked with command: $command")
            textResponse.text = command
            editCommand.text.clear()
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "MainActivity onResume")
    }
}
