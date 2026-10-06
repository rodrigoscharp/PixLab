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

tasks.test {
    bootJars.values.forEach { inputs.file(it) }
    systemProperty("pixlab.e2e.psp-jar", bootJars.getValue(":psp-simulator").get().asFile.absolutePath)
    systemProperty("pixlab.e2e.merchant-jar", bootJars.getValue(":merchant-core").get().asFile.absolutePath)
    systemProperty("pixlab.e2e.logs", layout.buildDirectory.dir("e2e-logs").get().asFile.absolutePath)
}
