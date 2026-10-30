// 独立的 Kotlin 预览工程：只编译纯逻辑层（解析 + 数据模型），
// 不依赖 Android SDK，也不被根项目的 settings.gradle 引用。
plugins {
    kotlin("jvm") version "2.1.0"
    application
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.wgj.switchquery.MainKt")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.wgj.switchquery.MainKt"
    }
}

// 控制台输出固定 UTF-8（Windows 终端请用 Windows Terminal 或先 chcp 65001）。
// parity 模式写文件时同样显式使用 UTF-8，不受控制台代码页影响。
tasks.named<JavaExec>("run") {
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}
