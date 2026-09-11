import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

// Read from gradle.properties rather than written here, so the desktop
// installer and the APK can never disagree about which release they are.
val resonateVersion = providers.gradleProperty("resonateVersion").get()

// Targets Java 17 bytecode using whichever JDK is running, rather than
// demanding a JDK 17 toolchain be installed. :app consumes this module and
// Android rejects class files newer than 17.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // Room is Android-only; the desktop build talks to the same SQLite file
    // shape through JDBC so a library can be moved between the two.
    implementation(libs.sqlite.jdbc)
    implementation(libs.jaudiotagger)
    implementation(libs.org.json)

    // Java Sound service providers. Adding these to the classpath teaches
    // AudioSystem to open MP3, OGG Vorbis and FLAC; WAV and AIFF are built in.
    // Roughly 1 MB in total, versus ~90 MB for a bundled ffmpeg.
    implementation(libs.mp3spi)
    implementation(libs.vorbisspi)
    implementation(libs.jflac)

    // Win32 access for the global duck hotkey. RegisterHotKey is the whole
    // reason: it asks the OS to forward one specific combination and nothing
    // else, unlike a low-level keyboard hook, which sees every keystroke on the
    // machine and is indistinguishable from a keylogger to antivirus software.
    implementation(libs.jna)
    implementation(libs.jna.platform)
}

compose.desktop {
    application {
        mainClass = "com.exo.musicplayer.desktop.MainKt"

        // Java reads neither HTTP_PROXY nor the Windows proxy settings on its
        // own, so on a machine behind a proxy every provider only reachable
        // through it silently returns nothing — Deezer and NetEase among them.
        // This makes the JVM honour the system configuration; where no proxy is
        // set, or traffic is already routed at the adapter, it changes nothing.
        jvmArgs("-Djava.net.useSystemProxies=true")

        // The About panel reads this back. Baking it in at package time means
        // the number on screen is the number the installer registered with
        // Windows, rather than a second copy that can drift.
        jvmArgs("-Dresonate.version=$resonateVersion")

        // Memory behaviour, deliberately without -Xmx.
        //
        // A hard heap cap is the usual way to make a JVM look small, and it is
        // also how a JVM starts throwing OutOfMemoryError at whatever the author
        // failed to anticipate — a 900-track bulk job, an enormous cover. These
        // flags instead let the heap grow as far as it needs and make the JVM
        // give the memory back afterwards, which the defaults never do: stock
        // MaxHeapFreeRatio is 70 and periodic GC is off entirely, so the heap
        // ratchets upward and stays there for the life of the process.
        // -Xms is a floor, not a ceiling: it cannot cause an OutOfMemoryError,
        // it only stops the JVM committing a large heap it has no use for. The
        // ergonomic default is 1/64 of physical RAM, which on a 32 GB machine
        // means half a gigabyte reserved at startup for an app whose live set is
        // around 15 MB. It grows on demand from here.
        jvmArgs("-Xms32m")
        jvmArgs("-XX:MinHeapFreeRatio=10")
        jvmArgs("-XX:MaxHeapFreeRatio=25")
        jvmArgs("-XX:G1PeriodicGCInterval=20000")
        jvmArgs("-XX:G1PeriodicGCSystemLoadThreshold=0")

        // Dispatchers.IO defaults to 64 threads. Nothing here needs that much
        // concurrency — the widest fan-out is seven catalogue lookups — and
        // every thread costs committed stack.
        jvmArgs("-Dkotlinx.coroutines.io.parallelism=12")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Resonate"

            // yt-dlp, ffmpeg and spotdl ship inside the app image rather than
            // being fetched on first use. They are the bulk of the installer,
            // but a music downloader that cannot download until you have found
            // and installed two other programs is not really a downloader.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))

            // The packaged runtime is a trimmed jlink image, so anything outside
            // java.base has to be asked for by name. Leaving these out builds
            // and runs fine under `gradle run` — which uses the full JDK — and
            // then fails only in the installed app, which is the worst possible
            // place to find out.
            modules(
                "java.sql",         // JDBC, for the library database
                "java.naming",      // required by java.sql
                "java.prefs",       // settings live in the registry via Preferences
                "jdk.crypto.ec",    // elliptic-curve TLS: most of the APIs need it
                "jdk.unsupported"   // sun.misc.Unsafe, used by the audio SPIs
            )
            // Drives the MSI ProductVersion, which is the whole of Windows'
            // upgrade logic: it has to increase for an installer to replace an
            // existing install instead of offering to remove it. jpackage
            // derives a fresh ProductCode from name plus version, so bumping
            // this is also what makes the new build a distinct product.
            packageVersion = resonateVersion
            windows {
                menuGroup = "Resonate"
                shortcut = true
                // Stable UUID so upgrades replace rather than stack.
                upgradeUuid = "6E3F1C42-6B7B-4C3E-9A1F-2B7D5E0A9C11"
            }
        }
    }
}

// ---- MSI upgrade repair ----------------------------------------------------
//
// jpackage sequences RemoveExistingProducts at 798, which WiX's own validator
// rejects (ICE27) because that is the search phase, not the execution phase.
// Left alone, the previous version is uninstalled outside the install
// transaction: nothing rolls it back if the new install then fails. The fix is
// one integer in the sequence table, applied to whatever jpackage just wrote.
//
// Wired with finalizedBy rather than folded into tools/build.sh so that a
// plain `gradle packageMsi` produces a correct installer too - an installer
// that is only correct when built through one particular script is a trap.
val fixMsiUpgradeSequence by tasks.registering(Exec::class) {
    description = "Moves RemoveExistingProducts into the MSI execution phase (ICE27)."
    // The MSI is rewritten in place, so there is nothing to be up to date about.
    outputs.upToDateWhen { false }

    // Both the debug and release packaging tasks are swept; the script ignores
    // directories that do not exist, so one call covers whichever ran.
    val msiDirs = listOf("main", "main-release").map {
        layout.buildDirectory.dir("compose/binaries/$it/msi").get().asFile.absolutePath
    }
    commandLine(
        listOf(
            "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
            rootProject.layout.projectDirectory.file("tools/fix-msi-upgrade.ps1")
                .asFile.absolutePath,
            "-MsiDir", msiDirs.joinToString(";")
        )
    )
}

listOf("packageMsi", "packageReleaseMsi").forEach { name ->
    tasks.matching { it.name == name }.configureEach { finalizedBy(fixMsiUpgradeSequence) }
}
