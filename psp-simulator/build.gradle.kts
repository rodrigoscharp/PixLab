plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":shared-contracts"))
    implementation(libs.bundles.service)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.bundles.service.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
