plugins {
    idea
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
    id("org.jooq.jooq-codegen-gradle") version "3.19.16"
}

group = "com.ender"
version = "0.0.1"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("software.amazon.awssdk:bom:2.28.19")
    }
}

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")

    // jOOQ
    implementation("org.jooq:jooq:3.19.16")
    implementation("org.jooq:jooq-kotlin:3.19.16")

    // Kotlin
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // JWT
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // Database
    runtimeOnly("com.mysql:mysql-connector-j")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")

    // AWS SDK v2
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:sqs")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.mockk:mockk:1.13.13")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("com.h2database:h2")

    // jOOQ code generation
    jooqCodegen("org.jooq:jooq-meta-extensions:3.19.16")
    jooqCodegen("com.mysql:mysql-connector-j")
}

idea {
    module {
        sourceDirs = sourceDirs + file("src/main/kotlin") + file("build/generated-sources/jooq")
        testSources.from(file("src/test/kotlin"))
    }
}

sourceSets {
    main {
        kotlin {
            srcDir("build/generated-sources/jooq")
        }
    }
}

jooq {
    configuration {
        generator {
            database {
                name = "org.jooq.meta.extensions.ddl.DDLDatabase"
                properties {
                    property {
                        key = "scripts"
                        value = "src/main/resources/db/migration/*.sql"
                    }
                    property {
                        key = "sort"
                        value = "flyway"
                    }
                    property {
                        key = "defaultNameCase"
                        value = "lower"
                    }
                }
            }
            generate {
                isDeprecated = false
                isRecords = true
                isFluentSetters = true
            }
            target {
                packageName = "com.ender.takehome.generated"
                directory = "build/generated-sources/jooq"
            }
        }
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.named("compileKotlin") {
    dependsOn("jooqCodegen")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
