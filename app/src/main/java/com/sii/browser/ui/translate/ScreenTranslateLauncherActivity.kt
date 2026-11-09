// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui.translate

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.sii.browser.ui.overlay.OverlayManager

/**
 * 透明授权宿主：只负责请求系统截屏授权，不显示浏览器主页；
 * 授权完成后启动翻译服务并立即退出，回到用户原来的应用。
 */
class ScreenTranslateLauncherActivity : AppCompatActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val loopOverride = when (intent.getStringExtra("screen_translate_mode")) {
                "loop" -> true
                "once" -> false
                else -> null
            }
            val regionSelect = intent.getBooleanExtra("screen_translate_region", false)
            ScreenTranslateService.start(this, result.resultCode, data, loopOverride, regionSelect)
        }
        closeSelf()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!OverlayManager.canDraw(this)) {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            closeSelf()
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            closeSelf()
            return
        }
        val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(
                android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
            )
        } else {
            manager.createScreenCaptureIntent()
        }
        runCatching { launcher.launch(captureIntent) }.onFailure { closeSelf() }
    }

    /** 授权页是独立任务，结束时连任务一起移除，否则会回到浏览器等上一个应用。 */
    private fun closeSelf() {
        finishAndRemoveTask()
        overridePendingTransition(0, 0)
    }
}
