// Sobe psp-simulator e merchant-core como processos separados (os bootJars) e testa o fluxo pela rede.
val bootJars = listOf(":psp-simulator", ":merchant-core").associateWith { path ->
    evaluationDependsOn(path)
    project(path).tasks.named<Jar>("bootJar").flatMap { it.archiveFile }
}

dependencies {
    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation(project(":shared-contracts"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.awaitility)
    testImplementation(libs.spring.web)
    testImplementation(libs.jackson.databind)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.rabbitmq)
    testRuntimeOnly(libs.junit.platform.launcher)
}

fun Test.labProperties() {
    bootJars.values.forEach { inputs.file(it) }
    inputs.dir(rootDir.resolve("chaos-profiles"))
    systemProperty("pixlab.e2e.psp-jar", bootJars.getValue(":psp-simulator").get().asFile.absolutePath)
    systemProperty("pixlab.e2e.merchant-jar", bootJars.getValue(":merchant-core").get().asFile.absolutePath)
    systemProperty("pixlab.e2e.logs", layout.buildDirectory.dir("e2e-logs").get().asFile.absolutePath)
    systemProperty("pixlab.e2e.chaos-profiles", rootDir.resolve("chaos-profiles").absolutePath)
    testLogging { showStandardStreams = true; events("passed", "failed") }
}

tasks.test {
    labProperties()
}

/** Reproduz uma execução de caos: ./gradlew chaos --chaos-profile=mixed --seed=42 [--payments=30] */
abstract class ChaosTask : Test() {
    @get:Input @get:Optional @get:Option(option = "chaos-profile", description = "perfil em chaos-profiles/")
    abstract val profile: Property<String>

    @get:Input @get:Optional @get:Option(option = "seed", description = "seed da execução")
    abstract val seed: Property<String>

    @get:Input @get:Optional @get:Option(option = "payments", description = "quantidade de pagamentos")
    abstract val payments: Property<String>
}

tasks.register<ChaosTask>("chaos") {
    description = "Roda uma execução de caos reproduzível contra os dois serviços."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("dev.pixlab.e2e.ChaosRunE2ETest") }
    outputs.upToDateWhen { false }
    labProperties()
    doFirst {
        profile.orNull?.let { systemProperty("pixlab.chaos.profile", it) }
        seed.orNull?.let { systemProperty("pixlab.chaos.seed", it) }
        payments.orNull?.let { systemProperty("pixlab.chaos.payments", it) }
    }
}
