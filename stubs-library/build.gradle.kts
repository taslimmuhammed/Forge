plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<JavaCompile>().configureEach {
    // This tells the compiler that the code in this module "belongs"
    // to the java.compiler module, effectively allowing the split package.
    options.compilerArgs.addAll(listOf(
        "--patch-module", "java.compiler=${sourceSets.main.get().java.srcDirs.joinToString(":")}"
    ))
}