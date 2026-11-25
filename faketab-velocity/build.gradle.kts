plugins {
    java
}

// Velocity API 4.x собрана под Java 25 (сам прокси 4.x тоже требует Java 25).
java.toolchain.languageVersion.set(JavaLanguageVersion.of(25))
tasks.withType<JavaCompile>().configureEach { options.release.set(25) }

dependencies {
    compileOnly("com.velocitypowered:velocity-api:4.3.1")
    annotationProcessor("com.velocitypowered:velocity-api:4.3.1")
}

tasks.jar {
    archiveFileName.set("FakeTabPreview-Velocity-${project.version}.jar")
}
