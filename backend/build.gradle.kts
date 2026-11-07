plugins {
    java
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "ma.jomhor"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // Flyway version comes from the Boot 4.1 BOM (12.x), not the latest 13.x: Boot's autoconfig is tested against it.
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    // Ticket PDF (GET /fan/tickets/{id}/pdf): PDFBox draws the page, ZXing core encodes the QR (tests decode with it too).
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    implementation("com.google.zxing:core:3.5.4")
    // Arabic text on the ticket PDF: ArabicShaping (presentation forms) + Bidi (visual order); PDFBox does neither.
    implementation("com.ibm.icu:icu4j:79.1")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // HolderNamesTest reads the shared holder-name cases. TokenVectorsTest and GateVectorsTest compare against (or,
    // with -Djomhor.writeVectors=true, rewrite) the docs vectors. TicketPdfTest checks the PDF lockup is the design file.
    inputs.files("../docs/token-vectors.json", "../docs/gate-vectors.json", "../docs/holder-name-vectors.json",
        "../design/logo/jomhor-lockup-light.svg")
    providers.systemProperty("jomhor.writeVectors").orNull?.let { systemProperty("jomhor.writeVectors", it) }
}

// A local `gradlew bootRun` runs the dev profile (local DB, fake payments, demo data) unless SPRING_PROFILES_ACTIVE says
// otherwise. Deployed builds run the jar, never bootRun.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    if (System.getenv("SPRING_PROFILES_ACTIVE").isNullOrBlank()) {
        systemProperty("spring.profiles.active", "dev")
    }
}
