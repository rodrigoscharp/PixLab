plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "pixlab"

include("shared-contracts", "psp-simulator", "merchant-core", "e2e-tests")
