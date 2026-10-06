plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":shared-contracts"))
    implementation(libs.bundles.service)
    implementation(libs.spring.boot.starter.amqp)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.bundles.service.test)
    testImplementation(libs.testcontainers.rabbitmq)
    testImplementation(libs.jqwik)
    testRuntimeOnly(libs.junit.platform.launcher)
}
