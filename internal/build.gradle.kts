group = "net.yakclient"
version = "1.0-SNAPSHOT"

repositories {
    mavenLocal()

}

dependencies {
//    api(project(":api"))
    implementation("io.github.config4k:config4k:0.4.2")
    implementation("com.typesafe:config:1.4.1")
    testImplementation("net.yakclient:bmu-api:1.0-SNAPSHOT")
    testImplementation("net.yakclient:bmu-mixin:1.0-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.7.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.7.0")
}

tasks.test {
    useJUnitPlatform()
}