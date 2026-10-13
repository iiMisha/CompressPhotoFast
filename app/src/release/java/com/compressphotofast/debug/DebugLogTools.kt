package com.compressphotofast.debug

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

/** Release-заглушка: журнал и отправка на сервер в release-сборке не выполняются. */
object DebugLogTools {
    fun init(context: Context) {}

    fun installUi(activity: AppCompatActivity, toolbar: MaterialToolbar) {}
}
