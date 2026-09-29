plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jodongbeom.voicetest"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jodongbeom.voicetest"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.$versionCode"
    }

    // 서명 열쇠는 GitHub Actions 에서만 환경변수로 들어옴 (저장소에는 없음)
    val keystoreFile = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        create("release") {
            if (keystoreFile != null) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "voice"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
                storeType = "pkcs12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/webassets"))
}

// 저장소 맨 위 index.html 을 앱에 넣음 (화면 파일은 하나만 관리)
val copyWebAssets by tasks.registering(Copy::class) {
    from(rootProject.file("../index.html"))
    into(layout.buildDirectory.dir("generated/webassets"))
}
tasks.named("preBuild") { dependsOn(copyWebAssets) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.media:media:1.7.0")
    testImplementation("junit:junit:4.13.2")
}

// CI 로그에 테스트별 결과가 보이도록
tasks.withType<Test>().configureEach {
    testLogging { events("passed", "failed", "skipped") }
}
