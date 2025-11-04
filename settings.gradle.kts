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

rootProject.name = "BiliVideoParser"