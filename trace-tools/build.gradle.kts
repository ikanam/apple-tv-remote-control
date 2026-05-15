plugins { alias(libs.plugins.kotlin.jvm); application }
dependencies { implementation(project(":protocol")); implementation(libs.coroutines.core) }
application { mainClass.set("dev.atvremote.tracetools.SmokeCliKt") }
kotlin { jvmToolchain(17) }

// Dedicated entry point for the SYNTHETIC golden-trace generator (Task 10).
// Runs the in-repo reference oracle end-to-end self-check, then (re)writes the
// deterministic fixtures into protocol/src/test/resources/goldentrace/.
//   ./gradlew :trace-tools:runGoldenTraceGen
//   ./gradlew :trace-tools:run -PgenGolden          (convenience alias)
tasks.register<JavaExec>("runGoldenTraceGen") {
    group = "verification"
    description = "Regenerate the synthetic golden-trace fixtures (deterministic, no RNG)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.atvremote.tracetools.GoldenTraceGenKt")
    // Generator resolves protocol/src/test/resources/goldentrace relative to repo root.
    workingDir = rootProject.projectDir
}

tasks.named<JavaExec>("run") {
    if (project.hasProperty("genGolden")) {
        mainClass.set("dev.atvremote.tracetools.GoldenTraceGenKt")
        workingDir = rootProject.projectDir
    }
}
