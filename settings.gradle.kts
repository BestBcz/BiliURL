pluginManagement {
    repositories {
        // 阿里云 Gradle 插件仓库
        maven("https://maven.aliyun.com/repository/gradle-plugin")

        // 阿里云 Maven 仓库
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/central")

        // Gradle 官方仓库
        gradlePluginPortal()
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
rootProject.name = "BiliVideoParser"