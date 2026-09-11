plugins {
    // Строковый синтаксис железобетонно связывает плагины с каталогом и версиями проекта
    apply(plugin = "com.android.application")
    apply(plugin = "org.jetbrains.kotlin.android")
    apply(plugin = "org.jetbrains.kotlin.kapt")

}

android {
    namespace = "com.example.univer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.univer"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    // CameraX (для работы с камерой весов)
    implementation("androidx.camera:camera-core:1.4.0")
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")

    // ML Kit Text Recognition (ИСПРАВЛЕНО: теперь версия строго 19.0.0, а не 190.0)
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.0")

    // Локальная База Данных Room (для хранения меню)
    val room_version = "2.6.1"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")

    // Используем строковую конфигурацию — она работает стабильно в любых версиях Gradle
    "kapt"("androidx.room:room-compiler:$room_version")

    // Компоненты UI (Сетки, карточки в стиле iiko)
    implementation("com.google.android.material:material:1.12.0")

    // Coil (для асинхронной загрузки картинок)
    implementation("io.coil-kt:coil:2.6.0")
}

// Современный синтаксис таргета Java компилятора Kotlin
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
