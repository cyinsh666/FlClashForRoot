import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import javax.inject.Inject

plugins {
    id("com.android.library")
}

android {
    namespace = "com.follow.clash.service"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndkVersion.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        // Unit tests live under android/tests/ instead of each module's src/test.
        getByName("test").java.setSrcDirs(listOf("../tests/service"))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":common"))
    implementation(libs.gson)
    implementation(libs.androidx.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

/** 独立生成 PIE 可执行文件，随后作为 native 库目录交给 AGP 打包，不参与 JNI 加载。 */
abstract class BuildRootTunHelper : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFile: RegularFileProperty

    @get:Internal
    abstract val ndkDirectory: DirectoryProperty

    @get:Input
    abstract val ndkVersion: Property<String>

    @get:Input
    abstract val androidApi: Property<Int>

    @get:Input
    abstract val targetAbis: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    /** 按 Flutter 实际构建的 ABI 编译，16KB 页参数与新版 Core 的打包要求一致。 */
    @TaskAction
    fun buildHelper() {
        val system = System.getProperty("os.name")
        val host = when {
            system.startsWith("Windows") -> "windows-x86_64"
            system.startsWith("Mac") -> "darwin-x86_64"
            else -> "linux-x86_64"
        }
        val compilerName = if (system.startsWith("Windows")) "clang++.exe" else "clang++"
        val compiler = ndkDirectory.get().asFile.resolve("toolchains/llvm/prebuilt/$host/bin/$compilerName")
        check(compiler.isFile) { "没有找到 Root TUN helper 所需的 NDK 编译器：$compiler" }
        val triples = mapOf(
            "armeabi-v7a" to "armv7a-linux-androideabi",
            "arm64-v8a" to "aarch64-linux-android",
            "x86_64" to "x86_64-linux-android",
        )
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        for (abi in targetAbis.get()) {
            val directory = output.resolve(abi).apply { mkdirs() }
            execOperations.exec {
                commandLine(
                    compiler.path,
                    "--target=${triples.getValue(abi)}${androidApi.get()}",
                    "-std=c++17", "-O2", "-fPIE", "-pie", "-static-libstdc++",
                    "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384",
                    sourceFile.get().asFile.path,
                    "-o", directory.resolve("libroot_tun_helper.so").path,
                )
            }
        }
    }
}

// 与 :core 的 gomobile 目标保持一致，避免为单 ABI 调试安装额外 ABI 产物。
val rootHelperAbiByPlatform = mapOf(
    "android-arm" to "armeabi-v7a",
    "android-arm64" to "arm64-v8a",
    "android-x64" to "x86_64",
)
val rootHelperAbis = (rootProject.findProperty("target-platform") as String?)
    ?.split(",")
    ?.map { rootHelperAbiByPlatform[it.trim()] ?: throw GradleException("Root helper 不支持目标平台 $it") }
    ?: rootHelperAbiByPlatform.values.toList()

val buildRootTunHelper = tasks.register<BuildRootTunHelper>("buildRootTunHelper") {
    sourceFile.set(layout.projectDirectory.file("src/main/cpp/root_tun_helper.cpp"))
    ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory)
    ndkVersion.set(libs.versions.ndkVersion)
    androidApi.set(libs.versions.minSdk.get().toInt().coerceAtLeast(24))
    targetAbis.set(rootHelperAbis)
    outputDirectory.set(layout.buildDirectory.dir("generated/rootTunHelper/jniLibs"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildRootTunHelper, BuildRootTunHelper::outputDirectory)
    }
}
