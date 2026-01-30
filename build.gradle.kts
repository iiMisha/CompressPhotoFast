// Корневой build файл проекта
plugins {
    id("com.android.application") version "9.0.0" apply false
    id("com.android.library") version "9.0.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("com.google.dagger.hilt.android") version "2.57.1" apply false
    id("com.google.devtools.ksp") version "2.3.2" apply false
}

// Задача для очистки проекта
tasks.register("clean", Delete::class) {
    delete(layout.buildDirectory)
} 