/*
 * Copyright 2023-2024 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode
import util.enableContextReceivers
import util.otherwise
import util.whenForIde

plugins {
    alias(libs.plugins.conventions.jvm)
    alias(libs.plugins.compiler.specific.module)
}

kotlin {
    explicitApi = ExplicitApiMode.Disabled

    enableContextReceivers()
}

dependencies {
    compileOnly(libs.kotlin.reflect)
    compileOnly(libs.kotlin.compiler.embeddable)
    whenForIde {
        compileOnly(libs.serialization.plugin.forIde) {
            isTransitive = false
        }
    } otherwise {
        compileOnly(libs.serialization.plugin)
    }
    implementation(projects.compilerPluginCommon)
}
