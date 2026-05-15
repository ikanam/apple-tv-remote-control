plugins { alias(libs.plugins.kotlin.jvm); application }
dependencies { implementation(project(":protocol")); implementation(libs.coroutines.core) }
application { mainClass.set("dev.atvremote.tracetools.SmokeCliKt") }
kotlin { jvmToolchain(17) }
