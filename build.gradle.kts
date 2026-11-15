plugins {
    java
    application
}

group = "io.github.evilalee.gatelab"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

application {
    mainClass.set("io.github.evilalee.gatelab.ui.Main")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.formdev:flatlaf:3.7.2")

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
