package com.example.campus_express

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "express/native")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    // permission_handler 只覆盖 READ_SMS；后台接收器还需要
                    // RECEIVE_SMS，Android 13+ 通知还需要 POST_NOTIFICATIONS，
                    // 三件套在这里补齐（未授予的才弹系统窗）。
                    "requestSmsPermissions" -> {
                        val wanted = mutableListOf(
                            Manifest.permission.READ_SMS,
                            Manifest.permission.RECEIVE_SMS,
                        )
                        if (android.os.Build.VERSION.SDK_INT >= 33) {
                            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        val missing = wanted.filter {
                            ContextCompat.checkSelfPermission(this, it) !=
                                PackageManager.PERMISSION_GRANTED
                        }
                        if (missing.isNotEmpty()) {
                            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
                        }
                        result.success(missing.isEmpty())
                    }
                    else -> result.notImplemented()
                }
            }
    }
}
