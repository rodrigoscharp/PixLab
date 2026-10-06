plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":shared-contracts"))
    implementation(libs.bundles.service)
    implementation(libs.spring.boot.starter.amqp)
    implementation(libs.spring.boot.starter.batch.jdbc)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.bundles.service.test)
    testImplementation(libs.testcontainers.rabbitmq)
    testImplementation(libs.spring.boot.micrometer.tracing.test)
    testImplementation(libs.jqwik)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootDir
}
