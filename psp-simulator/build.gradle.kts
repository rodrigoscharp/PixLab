plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":shared-contracts"))
    implementation(libs.bundles.service)
    implementation(libs.jackson.dataformat.yaml)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.bundles.service.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Perfis de caos ficam em chaos-profiles/ na raiz do repositório.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootDir
}
