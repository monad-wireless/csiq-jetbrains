import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    // Version comes from the settings plugin, which already put it on the
    // classpath. Repeating it here is an error in Gradle 9.
    id("org.jetbrains.intellij.platform")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

private val javaTarget = providers.gradleProperty("javaTarget").get()
private val localPlatform = providers.gradleProperty("platformLocalPath").orNull?.takeIf { it.isNotBlank() }

kotlin {
    // PyCharm 2026.2 ships platform classes at class-file major 69, so the
    // compiler must be able to READ Java 25. What it EMITS targets 21, which
    // every supported IDE runtime loads.
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(javaTarget))
    }
}

java {
    sourceCompatibility = JavaVersion.toVersion(javaTarget)
    targetCompatibility = JavaVersion.toVersion(javaTarget)
}

dependencies {
    intellijPlatform {
        if (localPlatform != null && file(localPlatform).exists()) {
            local(localPlatform)
        } else {
            create(
                IntelliJPlatformType.fromCode(providers.gradleProperty("platformType").get()),
                providers.gradleProperty("platformVersion").get(),
            )
        }
        // The platform test framework is deliberately NOT on the test
        // classpath. It registers a JUnit 5 session listener that only starts
        // inside an IDE test harness, and every test here exercises the format
        // and model layers, which have no platform dependency at all.
        pluginVerifier()
        zipSigner()
    }

    // Compression. zstd is what csid writes and carries native libraries for
    // every platform the IDE runs on, so a .csiq.zst opens without a system
    // zstd. commons-compress adds gzip, bzip2, xz, lzma and lz4 for captures
    // that came back from somewhere else repacked.
    //
    // These are bundled rather than taken from the IDE, which happens to ship
    // all three: a platform library is not part of the plugin API contract and
    // can be dropped between releases.
    implementation("com.github.luben:zstd-jni:1.5.7-17")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.12")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform {
    pluginConfiguration {
        name = "CSIQ"
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // A blank property means "no upper bound", which has to leave the
            // attribute out entirely. Writing an empty until-build makes the
            // descriptor invalid rather than open-ended.
            val until = providers.gradleProperty("pluginUntilBuild").orNull
            if (until.isNullOrBlank()) untilBuild.unset() else untilBuild = until
        }
    }
    pluginVerification {
        ides {
            // The IDE the plugin was compiled against: the installed one
            // locally, the downloaded one on CI, so a verification run costs no
            // second download. Add `recommended()` to check the whole
            // compatible range instead; it fetches one IDE per product family.
            if (localPlatform != null && file(localPlatform).exists()) {
                local(file(localPlatform))
            } else {
                create(
                    IntelliJPlatformType.fromCode(providers.gradleProperty("platformType").get()),
                    providers.gradleProperty("platformVersion").get(),
                )
            }
        }
    }
}

tasks {
    test {
        useJUnitPlatform()
        // Optional real data. CSIQ_TEST_CAPTURES names a directory of `.csiq`
        // files; CSIQ_TEST_SESSION names one archive session directory holding
        // a capture and its metadata.json. Without them the suite runs on the
        // checked-in fixtures alone.
        System.getenv("CSIQ_TEST_CAPTURES")?.let { environment("CSIQ_TEST_CAPTURES", it) }
        System.getenv("CSIQ_TEST_SESSION")?.let { environment("CSIQ_TEST_SESSION", it) }
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = false
        }
    }

    // The format layer has no IntelliJ dependency, so it can be shipped and
    // tested on its own. This jar is what a notebook or a CI check consumes.
    register<Jar>("formatJar") {
        archiveBaseName = "csiq-format"
        from(sourceSets.main.get().output) {
            include("io/monadcount/csiq/format/**")
        }
    }
}
