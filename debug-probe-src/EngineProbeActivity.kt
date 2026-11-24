package com.osfans.trime.debug

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView

/** Foreground, DUMP-protected debug entry; never opens an editor or microphone. */
class EngineProbeActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val label = TextView(this).apply {
            text = "牛码静态引擎回归\n仅使用测试进程中的新建私有目录。\n不会读取输入框、剪贴板或历史记录。\n正在校验，请稍候……"
            textSize = 20f
            setPadding(32, 64, 32, 32)
        }
        setContentView(label)
        Thread({
            val outcome = runCatching { EngineProbeReceiver.runIsolatedProbe(applicationContext) }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    label.text = if (outcome.isSuccess) "引擎检查已结束。\n详细结果由电脑读取本应用的 android-engine-check.json。" else "检查未能启动：${outcome.exceptionOrNull()?.javaClass?.simpleName}"
                }
            }
        }, "niuma-foreground-engine-check").start()
    }
}
