// The pure core: training plan, session engine, progress, data model, pump protocol.
// No Android here — it compiles and tests on any JVM in seconds. The architecture test
// (src/test/java/org/openpump/ArchitectureTest.java) fails the build on any android import.
plugins {
    `java-library`
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

dependencies {
    // org.json is part of the Android platform; the app gets the real one at runtime.
    compileOnly(libs.android.json)

    testImplementation(libs.android.json)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

/* ---- The original assertion harnesses, run unchanged ----------------------------------
 * About 35,000 checks written before this project used JUnit. They run as plain programs
 * (a non-zero exit fails the build) and migrate into JUnit suites a module at a time.
 * `./gradlew test` runs them first, then the JUnit tests. */

val flatSrc = layout.buildDirectory.dir("flat-src")

// WiringCheck's invariants read every source file from one flat folder, the layout the
// code had before it was split into modules.
//
// app/src/debug/java is read too. The diagnostic console (MainActivity) lives there so the
// release APK does not carry it, and moving it must not take it out of the checks: it is
// still an Android source that builds frames and touches the radio. WiringCheck fails if
// the console is missing from this folder, so dropping this line cannot pass silently.
val syncFlatSources by tasks.registering(Sync::class) {
    from("src/main/java/org/openpump")
    from("src/test/java/org/openpump")
    from(rootProject.file("app/src/main/java/org/openpump"))
    from(rootProject.file("app/src/debug/java/org/openpump"))
    into(flatSrc)
}

fun harness(taskName: String, main: String, configure: JavaExec.() -> Unit = {}) =
    tasks.register<JavaExec>(taskName) {
        group = "verification"
        description = "Runs the $main assertion harness."
        classpath = sourceSets["test"].runtimeClasspath
        mainClass.set(main)
        workingDir = rootDir
        configure()
    }

val selfTest = harness("selfTest", "Run")
val migrateCheck = harness("migrateCheck", "Migrate")
val scenarioCheck = harness("scenarioCheck", "Scenarios")
val wiringCheck = harness("wiringCheck", "WiringCheck") {
    dependsOn(syncFlatSources)
    systemProperty("wiring.src", flatSrc.get().asFile.absolutePath)
}

val legacyHarness by tasks.registering {
    group = "verification"
    description = "Runs every original assertion harness."
    dependsOn(selfTest, migrateCheck, wiringCheck, scenarioCheck)
}

tasks.test {
    useJUnitPlatform()
    dependsOn(legacyHarness)
    // TrainerGuideDocTest checks docs/trainer-guide.md against the code, so an edit to the
    // guide alone must re-run the tests rather than leave them UP-TO-DATE.
    inputs.file(rootProject.file("docs/trainer-guide.md"))
        .withPropertyName("trainerGuide")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

/* ---- Re-check a session journal a phone sent ------------------------------------------
 * ./gradlew :core:journalCheck -Pfile=path/to/session-yyyyMMdd-HHmmss.txt
 * Replays the log through today's JournalWatch rules and prints the findings. */
tasks.register<JavaExec>("journalCheck") {
    group = "verification"
    description = "Replays a session journal (-Pfile=<path>) through JournalWatch."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.openpump.JournalCheck")
    workingDir = rootDir
    val f = providers.gradleProperty("file").orNull
    args(if (f == null) "" else rootProject.file(f).absolutePath)
}
