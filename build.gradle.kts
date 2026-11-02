import java.util.zip.ZipFile
import java.net.URLClassLoader

plugins {
    kotlin("jvm") version "1.8.0"
    id("net.mamoe.mirai-console") version "2.16.0"
}


group = "com.bcz"
version = "2.4.0"

repositories {
    maven("https://maven.aliyun.com/repository/public") // 加速依赖下载
    maven("https://maven.mamoe.net/releases") // Mirai 依赖
    mavenCentral() // 加载依赖
}



dependencies {
    implementation(kotlin("stdlib"))
    compileOnly("net.mamoe:mirai-core:2.16.0")
    compileOnly("net.mamoe:mirai-console:2.16.0")
    implementation("com.google.code.gson:gson:2.8.9")
    implementation("com.squareup.okio:okio:3.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    compileOnly("net.mamoe:mirai-core-utils:2.16.0")
    testImplementation(kotlin("test-junit"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
tasks.test {
    inputs.property("bilibiliLiveTests", providers.environmentVariable("BILIURL_LIVE_TESTS").orElse("false"))
    inputs.property("ffmpegTestExecutable", providers.environmentVariable("BILIURL_TEST_FFMPEG").orElse(""))
}
val buildPluginWithDependencies by tasks.registering(Jar::class) {
    group = "mirai"
    description = "Build a Mirai plugin with its third-party JVM dependencies bundled."
    dependsOn(tasks.classes)
    archiveClassifier.set("with-dependencies")
    archiveExtension.set("mirai.jar")
    destinationDirectory.set(layout.buildDirectory.dir("mirai"))
    from(sourceSets.main.get().output)
    from({ project.configurations.runtimeClasspath.get().filter { it.isFile }.map { zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}

val packageRelease by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Collect the single plugin and bundled plugin into build/release."
    dependsOn(tasks.test)
    from(tasks.named("buildPlugin"))
    from(buildPluginWithDependencies)
    into(layout.buildDirectory.dir("release"))
}

tasks.register("verifyReleaseArtifacts") {
    group = "verification"
    description = "Verify both release variants contain the plugin and expected dependency layout."
    dependsOn(packageRelease)
    doLast {
        val folder = layout.buildDirectory.dir("release").get().asFile
        val thin = folder.resolve("${project.name}-${project.version}.mirai2.jar")
        val bundled = folder.resolve("${project.name}-${project.version}-with-dependencies.mirai.jar")
        check(folder.listFiles { file -> file.extension == "jar" }?.size == 2) { "Expected exactly two release JARs" }
        for (file in listOf(thin, bundled)) {
            ZipFile(file).use { zip ->
                check(zip.getEntry("com/bcz/bilivideoparser/BiliVideoParser.class") != null)
                check(zip.getEntry("META-INF/services/net.mamoe.mirai.console.plugin.jvm.JvmPlugin") != null)
                val hasDependencies = zip.getEntry("okhttp3/OkHttpClient.class") != null &&
                    zip.getEntry("com/google/zxing/qrcode/QRCodeWriter.class") != null &&
                    zip.getEntry("com/google/gson/Gson.class") != null
                check(hasDependencies == (file == bundled)) { "Wrong dependency layout: ${file.name}" }
                check(zip.getEntry("net/mamoe/mirai/console/plugin/jvm/KotlinPlugin.class") == null) { "Mirai must be provided by the host" }
                check((zip.getEntry("META-INF/mirai-console-plugin/dependencies-private.txt") != null) == (file == thin))
            }
        }
        URLClassLoader(arrayOf(bundled.toURI().toURL()), null).use { loader ->
            for (className in listOf("okhttp3.OkHttpClient", "com.google.gson.Gson", "com.google.zxing.qrcode.QRCodeWriter")) {
                loader.loadClass(className).getDeclaredConstructor().newInstance()
            }
        }
    }
}
