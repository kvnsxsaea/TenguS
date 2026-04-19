plugins {
    kotlin("jvm") version "1.9.25"
    application
}

group = "com.tengus"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val playwrightVersion = "1.49.0"
val rabbitMqVersion = "5.22.0"
val jacksonVersion = "2.18.2"
val kotestVersion = "5.9.1"
val snakeYamlVersion = "2.3"
val logbackVersion = "1.5.12"
val slf4jVersion = "2.0.16"

dependencies {
    // Kotlin
    implementation(kotlin("reflect"))

    // Playwright
    implementation("com.microsoft.playwright:playwright:$playwrightVersion")

    // RabbitMQ
    implementation("com.rabbitmq:amqp-client:$rabbitMqVersion")

    // Jackson
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")

    // YAML configuration
    implementation("org.yaml:snakeyaml:$snakeYamlVersion")

    // Logging
    implementation("org.slf4j:slf4j-api:$slf4jVersion")
    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")

    // Testing
    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-property:$kotestVersion")
    testImplementation("io.mockk:mockk:1.13.13")
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

application {
    mainClass.set("com.tengus.ApplicationKt")
}
