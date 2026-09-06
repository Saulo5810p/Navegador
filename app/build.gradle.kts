plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xaulinxs.aosp.browser"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xaulinxs.aosp.browser"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Evita compressão do apk embutido em assets - o WebViewUpgrade
    // precisa ler o aosp_webview.apk como um bloco de bytes contíguo.
    androidResources {
        noCompress += "apk"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // androidx.appcompat removido: MainActivity agora é Activity pura e o
    // tema é android:Theme.Light.NoTitleBar, sem Material/AppCompat em
    // lugar nenhum do app (ver themes.xml). androidx.core:core-ktx
    // continua presente (FileProvider, ActivityCompat/ContextCompat para
    // a permissão de notificações, NotificationCompat) - é AndroidX
    // utilitário puro, não é Material Design nem AppCompat.

    // Coroutines: usadas só pelo DownloadForegroundService, pro loop de
    // monitoramento do DownloadManager (ver com.xaulinxs.funcoes.download).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // WebViewUpgrade NÃO vem mais do Maven Central (a versão publicada,
    // 0.1.0, é mais antiga que o código-fonte do GitHub e não tem a
    // correção de "Safer dynamic code loading" do Android 14). O
    // código-fonte da lib foi copiado direto pra
    // app/src/main/java/com/norman/webviewup/ com a correção já aplicada.
}
