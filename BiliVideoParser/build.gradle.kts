plugins {
    kotlin("jvm") version "1.8.0"
    id("net.mamoe.mirai-console") version "2.15.0"
    id("com.github.johnrengelman.shadow") version "8.1.1" // 确保使用最新版本
}

group = "com.bcz"
version = "1.0.0"

repositories {
    maven("https://maven.aliyun.com/repository/public") // 加速依赖下载
    maven("https://maven.mamoe.net/releases") // Mirai 依赖
    mavenCentral() // 必须保留 mavenCentral 用于加载依赖
}

dependencies {
    implementation(kotlin("stdlib"))
    compileOnly("net.mamoe:mirai-core:2.15.0")
    compileOnly("net.mamoe:mirai-console:2.15.0")
    implementation("com.google.code.gson:gson:2.8.2")
}