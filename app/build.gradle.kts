plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("jacoco")
}

import java.text.SimpleDateFormat
import java.util.Date

android {
    namespace = "com.compressphotofast"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.compressphotofast"
        minSdk = 29
        targetSdk = 36
        versionCode = 2

        // Динамическое формирование версии с датой и временем
        val baseVersion = project.findProperty("VERSION_NAME_BASE") as String? ?: "2.2.10"
        versionName = getBuildVersion(baseVersion)

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["coverage"] = "true"
        testInstrumentationRunnerArguments["useTestStorageService"] = "true"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
        animationsDisabled = true

        // Таймаут для instrumentation тестов (15 минут)
        // Предотвращает прерывание тестов агентом при длительном выполнении
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
            buildConfigField("boolean", "DEBUG_LOGGING", "true")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("boolean", "DEBUG_LOGGING", "false")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE-notice.md"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/NOTICE"
            pickFirsts.add("META-INF/LICENSE.md")
            pickFirsts.add("META-INF/NOTICE.md")
            pickFirsts.add("META-INF/LICENSE-notice.md")
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }

    applicationVariants.configureEach {
        val variant = this
        outputs.configureEach {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val appName = rootProject.name.replace(" ", "")
            val versionName = variant.versionName
            
            val suffix = if (variant.buildType.name == "debug") "_debug" else ""
            output.outputFileName = "${appName}_v${versionName}${suffix}.apk"
        }
    }
}

// Функция для формирования версии с датой и временем сборки
fun getBuildVersion(baseVersion: String): String {
        val dateFormat = SimpleDateFormat("dd.MM.yyyy-HHmm")
    val dateTime = dateFormat.format(Date())
    return "${baseVersion}(${dateTime})"
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    // AndroidX Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.2")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("androidx.activity:activity-ktx:1.10.1")
    
    // WorkManager для фоновых задач
    implementation("androidx.work:work-runtime-ktx:2.10.3")
    
    // Hilt для WorkManager
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    
    // Coil для загрузки изображений
    implementation("io.coil-kt.coil3:coil:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    
    // Compressor для сжатия изображений
    implementation("id.zelory:compressor:3.0.1")
    
    // ExifInterface для работы с метаданными
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    
    // DataStore для хранения настроек
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    
    // Material Design
    implementation("com.google.android.material:material:1.12.0")
    
    // Hilt для внедрения зависимостей
    implementation("com.google.dagger:hilt-android:2.57.1")
    ksp("com.google.dagger:hilt-android-compiler:2.57.1")
    
    // Timber для логирования
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Тестирование
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")

    // Unit Testing
    testImplementation("org.robolectric:robolectric:4.11")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("com.google.truth:truth:1.4.4")
    testImplementation("androidx.work:work-testing:2.10.3")

    // Instrumentation Testing
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-contrib:3.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.6.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("io.mockk:mockk-android:1.13.10")
    androidTestImplementation("androidx.work:work-testing:2.10.3")
    androidTestImplementation("com.google.truth:truth:1.4.4")

    // Android Test Orchestrator
    androidTestUtil("androidx.test:orchestrator:1.5.0")
    androidTestUtil("androidx.test.services:test-services:1.5.0")

    // Hilt Testing
    testImplementation("com.google.dagger:hilt-android-testing:2.57.1")
    kspTest("com.google.dagger:hilt-compiler:2.57.1")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.57.1")
    kspAndroidTest("com.google.dagger:hilt-compiler:2.57.1")

    // Coverage
    testImplementation("org.jacoco:org.jacoco.core:0.8.11")
}

// Настройка Jacoco для покрытия кода
tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }

    sourceDirectories.setFrom(files("${project.layout.projectDirectory.dir("src/main/java")}"))
    classDirectories.setFrom(files("${project.layout.buildDirectory.get()}/tmp/kotlin-classes/debug"))
    // ИСПРАВЛЕНИЕ: Добавлены оба возможных пути к .exec файлу для надёжности
    // Стандартный путь + альтернативный путь (где на самом деле создаётся файл)
    executionData.setFrom(
        files(
            "${project.layout.buildDirectory.get()}/jacoco/testDebugUnitTest.exec",
            "${project.layout.buildDirectory.get()}/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"
        )
    )

    // Исключения для сгенерированных файлов
    val exclusions = listOf(
        // Hilt сгенерированные файлы
        "**/di/*_Factory.class",
        "**/di/*_MembersInjector.class",
        "**/Hilt_*.*",
        // ViewBinding сгенерированные файлы
        "**/databinding/*.*",
        "**/android/databinding/*.*",
        // DataBinding сгенерированные файлы
        "**/BuildConfig.*",
        // R файлы
        "**/R.class",
        "**/R$*.class",
        // BR файлы
        "**/BR.class"
    )

    classDirectories.setFrom(files("${project.layout.buildDirectory.get()}/tmp/kotlin-classes/debug").asFileTree.matching {
        exclude(exclusions)
    })
}

// Задача для проверки минимального coverage
tasks.register<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    group = "verification"
    description = "Verifies that the code coverage is at least 30%"

    dependsOn("jacocoTestReport")

    violationRules {
        rule {
            limit {
                minimum = "0.30".toBigDecimal()
            }
        }
    }

    sourceDirectories.setFrom(files("${project.layout.projectDirectory.dir("src/main/java")}"))
    classDirectories.setFrom(files("${project.layout.buildDirectory.get()}/tmp/kotlin-classes/debug"))
    executionData.setFrom(files("${project.layout.buildDirectory.get()}/jacoco/testDebugUnitTest.exec"))

    // Те же исключения для сгенерированных файлов
    val exclusions = listOf(
        // Hilt сгенерированные файлы
        "**/di/*_Factory.class",
        "**/di/*_MembersInjector.class",
        "**/Hilt_*.*",
        // ViewBinding сгенерированные файлы
        "**/databinding/*.*",
        "**/android/databinding/*.*",
        // DataBinding сгенерированные файлы
        "**/BuildConfig.*",
        // R файлы
        "**/R.class",
        "**/R$*.class",
        // BR файлы
        "**/BR.class"
    )

    classDirectories.setFrom(files("${project.layout.buildDirectory.get()}/tmp/kotlin-classes/debug").asFileTree.matching {
        exclude(exclusions)
    })
}

// Задача для объединенного coverage отчета (Unit + Instrumentation)
tasks.register<JacocoReport>("jacocoCombinedTestReport") {
    group = "verification"
    description = "Generates combined coverage report from unit and instrumentation tests"

    dependsOn("testDebugUnitTest", "connectedDebugAndroidTest")

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }

    sourceDirectories.setFrom(files("${project.layout.projectDirectory.dir("src/main/java")}"))
    classDirectories.setFrom(files("${project.layout.buildDirectory.get()}/tmp/kotlin-classes/debug"))

    // Объединение execution data из unit и instrumentation тестов
    val unitTestExec = file("${project.layout.buildDirectory.get()}/jacoco/testDebugUnitTest.exec")
    val androidTestExecDir = file("${project.layout.buildDirectory.get()}/outputs/code_coverage/debugAndroidTest/connected/")
    val androidTestExecAlt = file("${project.layout.buildDirectory.get()}/outputs/unit_test_code_coverage/debugUnitTest/")
    val coverageBuildDir = file("${project.layout.buildDirectory.get()}/outputs/coverage/")
    val jacocoExecPath = "${project.layout.buildDirectory.get()}/outputs/androidTest-results/connected/"

    val executionFiles = mutableListOf<File>()
    if (unitTestExec.exists()) {
        executionFiles.add(unitTestExec)
    }
    // Поиск .ec файлов в возможных locations для AGP 9.0+
    if (androidTestExecDir.exists()) {
        executionFiles.addAll(fileTree(androidTestExecDir).matching { include("**/*.ec") })
    }
    if (androidTestExecAlt.exists()) {
        executionFiles.addAll(fileTree(androidTestExecAlt).matching { include("**/*.ec") })
    }
    if (coverageBuildDir.exists()) {
        executionFiles.addAll(fileTree(coverageBuildDir).matching { include("**/*.ec") })
    }
    // Также ищем в стандартной директории androidTest-results
    fileTree(jacocoExecPath).matching { include("**/*.ec") }.files.forEach { ecFile ->
        executionFiles.add(ecFile)
    }

    executionData.setFrom(files(executionFiles))

    // Те же исключения для сгенерированных файлов
    val exclusions = listOf(
        // Hilt сгенерированные файлы
        "**/di/*_Factory.class",
        "**/di/*_MembersInjector.class",
        "**/Hilt_*.*",
        // ViewBinding сгенерированные файлы
        "**/databinding/*.*",
        "**/android/databinding/*.*",
        // DataBinding сгенерированные файлы
        "**/BuildConfig.*",
        // R файлы
        "**/R.class",
        "**/R$*.class",
        // BR файлы
        "**/BR.class"
    )

    classDirectories.setFrom(files("${project.layout.buildDirectory.get()}/tmp/kotlin-classes/debug").asFileTree.matching {
        exclude(exclusions)
    })
}

// Задача для создания coverage отчета instrumentation тестов отдельно
tasks.register<JacocoReport>("jacocoAndroidTestReport") {
    group = "verification"
    description = "Generates coverage report from instrumentation tests only"

    dependsOn("connectedDebugAndroidTest")

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }

    sourceDirectories.setFrom(files("${project.layout.projectDirectory.dir("src/main/java")}"))
    classDirectories.setFrom(files("${project.layout.buildDirectory.get()}/tmp/kotlin-classes/debug"))

    val androidTestExecDir = file("${project.layout.buildDirectory.get()}/outputs/code_coverage/debugAndroidTest/connected/")
    val jacocoExecPath = "${project.layout.buildDirectory.get()}/outputs/androidTest-results/connected/"

    val executionFiles = mutableListOf<File>()
    if (androidTestExecDir.exists()) {
        executionFiles.addAll(fileTree(androidTestExecDir).matching { include("**/*.ec") })
    }
    fileTree(jacocoExecPath).matching { include("**/*.ec") }.files.forEach { ecFile ->
        executionFiles.add(ecFile)
    }

    executionData.setFrom(files(executionFiles))

    val exclusions = listOf(
        "**/di/*_Factory.class",
        "**/di/*_MembersInjector.class",
        "**/Hilt_*.*",
        "**/databinding/*.*",
        "**/android/databinding/*.*",
        "**/BuildConfig.*",
        "**/R.class",
        "**/R$*.class",
        "**/BR.class"
    )

    classDirectories.setFrom(files("${project.layout.buildDirectory.get()}/tmp/kotlin-classes/debug").asFileTree.matching {
        exclude(exclusions)
    })
}

// Параллельный запуск тестов с поддержкой режимов
tasks.withType<Test> {
    maxHeapSize = "2048m"
    jvmArgs("-XX:MaxMetaspaceSize=512m")

    // Определение режима через переменную окружения GRADLE_MODE
    // Возможные значения: "eco" (по умолчанию) или "fast"
    val gradleMode = System.getenv("GRADLE_MODE") ?: "eco"

    maxParallelForks = when (gradleMode) {
        "fast" -> (Runtime.getRuntime().availableProcessors() / 2).coerceAtMost(4)  // Параллельное выполнение для быстрой сборки
        else -> 1  // Последовательное выполнение для экономии CPU (по умолчанию)
    }

    systemProperty("junit.jupiter.execution.parallel.enabled", gradleMode == "fast")
}

// Полная проверка всех тестов
tasks.register("checkAllTests") {
    description = "Запуск всех тестов с coverage"
    group = "verification"

    dependsOn("testDebugUnitTest")
    dependsOn("connectedDebugAndroidTest")
    dependsOn("jacocoTestReport")

    doLast {
        println("✅ Все тесты выполнены")
        println("📊 Coverage отчет: app/build/reports/jacoco/index.html")
    }
}