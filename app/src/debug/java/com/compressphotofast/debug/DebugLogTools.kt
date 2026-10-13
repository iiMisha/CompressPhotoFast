package com.compressphotofast.debug

import android.content.Context
import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.compressphotofast.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Инструменты диагностики, доступные только в debug-сборке: файловый журнал,
 * отправка на сервер логов и меню в тулбаре главного экрана.
 * В release-сборке одноимённый объект пустой (см. `app/src/release`).
 */
object DebugLogTools {

    /** Вызывается из `CompressPhotoApp.onCreate` только при `BuildConfig.DEBUG`. */
    fun init(context: Context) {
        FileLog.init(context)
        Timber.plant(FileTree())
        installCrashHandler()
    }

    /** Добавляет меню журнала в тулбар [toolbar]. */
    fun installUi(activity: AppCompatActivity, toolbar: MaterialToolbar) {
        toolbar.inflateMenu(R.menu.debug_menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_send_log -> sendLog(activity)
                R.id.action_clear_log -> clearLog(activity)
                R.id.action_log_server -> showServerDialog(activity)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
    }

    /** Записывает необработанные исключения в журнал и передаёт их прежнему обработчику. */
    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev is CrashLogger) return
        Thread.setDefaultUncaughtExceptionHandler(CrashLogger(prev))
    }

    private class CrashLogger(private val prev: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            FileLog.write(Log.ERROR, "CRASH", "в потоке ${t.name}", e)
            prev?.uncaughtException(t, e)
        }
    }

    private fun sendLog(activity: AppCompatActivity) {
        if (!LogServer.isConfigured(activity)) {
            toast(activity, R.string.debug_log_server_not_configured)
            showServerDialog(activity)
            return
        }
        activity.lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) { LogServer.upload(activity) }
            if (error == null) {
                toast(activity, R.string.debug_log_sent)
            } else {
                Toast.makeText(activity, activity.getString(R.string.debug_log_send_failed, error), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun clearLog(activity: AppCompatActivity) {
        toast(activity, if (FileLog.clear()) R.string.debug_log_cleared else R.string.debug_log_clear_failed)
    }

    /** Диалог с полями URL и токена сервера логов. */
    private fun showServerDialog(activity: AppCompatActivity) {
        val padding = (20 * activity.resources.displayMetrics.density).toInt()
        val urlInput = EditText(activity).apply {
            hint = activity.getString(R.string.debug_server_url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(LogServer.getUrl(activity))
        }
        val tokenInput = EditText(activity).apply {
            hint = activity.getString(R.string.debug_server_token_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setText(LogServer.getToken(activity))
        }
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(urlInput)
            addView(tokenInput)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.debug_server_title)
            .setView(form)
            .setNegativeButton(R.string.debug_server_cancel, null)
            .setPositiveButton(R.string.debug_server_save) { _, _ ->
                LogServer.save(activity, urlInput.text.toString(), tokenInput.text.toString())
                toast(activity, R.string.debug_server_saved)
            }
            .show()
    }

    private fun toast(context: Context, resId: Int) {
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
    }
}
