import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // org.json is part of the Android platform; on the JVM it is only needed for tests.
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // `-PfixtureOut=<file>` writes reference games used to cross-check the JavaScript engine (belot-web).
    providers.gradleProperty("fixtureOut").orNull?.let {
        systemProperty("fixture.out", it)
        providers.gradleProperty("fixtureGames").orNull?.let { n -> systemProperty("fixture.games", n) }
        outputs.upToDateWhen { false }
    }
}
