plugins {
    kotlin("jvm")
    application
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":kformik"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

application {
    // Default runnable. Override with `-PrunExample=<name>` to switch.
    mainClass.set(
        when (project.findProperty("runExample")) {
            "nested" -> "io.kformik.examples.nested.NestedExampleKt"
            "async" -> "io.kformik.examples.async.AsyncValidationExampleKt"
            "typed" -> "io.kformik.examples.typed.TypedDataClassExampleKt"
            "fieldlevel" -> "io.kformik.examples.fieldlevel.FieldLevelValidationExampleKt"
            "dependent" -> "io.kformik.examples.dependent.DependentFieldsExampleKt"
            "debounced" -> "io.kformik.examples.debounced.DebouncedAutoSaveExampleKt"
            "wizard" -> "io.kformik.examples.wizard.MultistepWizardExampleKt"
            "fieldarray" -> "io.kformik.examples.fieldarray.FieldArrayExampleKt"
            "schema" -> "io.kformik.examples.schema.SchemaValidationExampleKt"
            else -> "io.kformik.examples.login.LoginExampleKt"
        }
    )
}
