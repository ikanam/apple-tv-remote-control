plugins { alias(libs.plugins.kotlin.jvm) }
dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.bouncycastle)
    implementation(libs.jmdns)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}
tasks.test { useJUnitPlatform() }
kotlin { jvmToolchain(17) }
