plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.compressphotofast"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.compressphotofast"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "2.2.7(30.08.2025)"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    // Настройка имени выходного APK для release версии
    applicationVariants.configureEach {
        val variant = this
        outputs.configureEach {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            if (variant.buildType.name == "release") {
                val appName = rootProject.name.replace(" ", "")
                val versionName = variant.versionName
                
                output.outputFileName = "${appName}_v${versionName}.apk"
            }
        }
    }
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
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}