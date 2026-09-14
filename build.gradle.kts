plugins {
    kotlin("jvm") version "2.4.10"
    id("io.ktor.plugin") version "3.5.1"
    application
}

group = "com.impulsosocial"
version = "1.3.9"

repositories { mavenCentral() }

application {
    mainClass.set("com.impulsosocial.server.ServerMainKt")
}

ktor {
    fatJar {
        archiveFileName.set("krediplus-server-all.jar")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

dependencies {
    implementation("io.ktor:ktor-server-core:3.5.1")
    implementation("io.ktor:ktor-server-netty:3.5.1")
    implementation("io.ktor:ktor-server-content-negotiation:3.5.1")
    implementation("io.ktor:ktor-serialization-gson:3.5.1")
    implementation("io.ktor:ktor-server-auth:3.5.1")
    implementation("io.ktor:ktor-server-auth-jwt:3.5.1")
    implementation("io.ktor:ktor-server-status-pages:3.5.1")
    implementation("io.ktor:ktor-server-call-logging:3.5.1")
    implementation("io.ktor:ktor-server-call-id:3.5.1")
    implementation("io.ktor:ktor-server-default-headers:3.5.1")
    implementation("io.ktor:ktor-server-cors:3.5.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-io-core-jvm:0.9.0")
    implementation("com.auth0:java-jwt:4.5.0")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("org.slf4j:slf4j-api:2.0.18")
    implementation("org.eclipse.angus:jakarta.mail:2.0.3")

    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("org.postgresql:postgresql:42.7.12")
    implementation("de.mkammerer:argon2-jvm:2.12")
    implementation("org.apache.poi:poi-ooxml:5.5.1")
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.google.zxing:javase:3.5.4")
    implementation("ch.qos.logback:logback-classic:1.5.31")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:3.5.1")
}

tasks.test {
    // Activar PostgreSQL cambia el alcance real de pruebas y debe invalidar su caché.
    inputs.property("postgresIntegrationEnabled", providers.environmentVariable("TEST_DATABASE_URL").isPresent)
}
