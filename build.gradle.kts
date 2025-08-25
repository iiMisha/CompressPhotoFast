// Корневой build файл проекта
plugins {
    id("com.android.application") version "8.12.1" apply false
    id("com.android.library") version "8.12.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("org.jetbrains.kotlin.jvm") version "1.9.22" apply false
    id("com.google.dagger.hilt.android") version "2.51.1" apply false
}

// Задача для очистки проекта
tasks.register("clean", Delete::class) {
    delete(layout.buildDirectory)
} 