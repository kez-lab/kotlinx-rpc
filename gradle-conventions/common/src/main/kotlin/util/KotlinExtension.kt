/*
 * Copyright 2023-2024 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package util

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.the
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

fun Project.kmp(block: KotlinMultiplatformExtension.() -> Unit) {
    configure(block)
}

const val KOTLIN_MULTIPLATFORM_PLUGIN_ID = "org.jetbrains.kotlin.multiplatform"
const val KOTLIN_JVM_PLUGIN_ID = "org.jetbrains.kotlin.jvm"

fun Project.withKotlinJvmExtension(action: Action<KotlinJvmProjectExtension>) {
    plugins.withId(KOTLIN_JVM_PLUGIN_ID) {
        the<KotlinJvmProjectExtension>().apply { action.execute(this) }
    }
}

fun Project.withKotlinKmpExtension(action: Action<KotlinMultiplatformExtension>) {
    plugins.withId(KOTLIN_MULTIPLATFORM_PLUGIN_ID) {
        the<KotlinMultiplatformExtension>().apply { action.execute(this) }
    }
}
