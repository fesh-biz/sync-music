import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

// MSI versions must be MAJOR.MINOR.BUILD with BUILD <= 65535.
val appVersion = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.partial.content)
    implementation(libs.ktor.server.auto.head.response)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.zxing.core)
    implementation(libs.slf4j.nop)
}

val buildInfoDir = layout.buildDirectory.dir("generated/buildinfo")
val generateBuildInfo by tasks.registering {
    val out = buildInfoDir
    val version = appVersion
    inputs.property("version", version)
    outputs.dir(out)
    doLast {
        val file = out.get().file("syncmusic-version.txt").asFile
        file.parentFile.mkdirs()
        file.writeText(version)
    }
}

sourceSets {
    main {
        resources.srcDir(generateBuildInfo)
    }
}

compose.desktop {
    application {
        mainClass = "biz.fesh.syncmusic.desktop.MainKt"
        jvmArgs += listOf(
            "-Xmx512m",
            "-Dfile.encoding=UTF-8",
            // Class-data sharing archive created on first run, makes later launches faster (JDK 19+).
            // IgnoreUnrecognizedVMOptions keeps older runtimes from refusing to start.
            "-XX:+IgnoreUnrecognizedVMOptions",
            "-XX:+AutoCreateSharedArchive",
            "-XX:SharedArchiveFile=\$APPDIR/syncmusic.jsa",
        )

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            // Keep everything ASCII here: WiX fails on non-Latin text in MSI metadata.
            packageName = "SyncMusic"
            packageVersion = appVersion
            description = "Sync Music - music sync between PC and phone"
            vendor = "fesh-biz"
            copyright = "fesh-biz"
            includeAllModules = true

            windows {
                menuGroup = "Sync Music"
                menu = true
                shortcut = true
                perUserInstall = true
                dirChooser = false
                upgradeUuid = "8f0e5f43-7c1e-4b8e-9a51-3d2a6c9b7e21"
                iconFile.set(project.file("src/main/packaging/icon.ico"))
            }
        }
    }
}
