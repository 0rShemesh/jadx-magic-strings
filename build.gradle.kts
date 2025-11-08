plugins {
	java
}

group = "io.github.skylot"
version = "1.0.1"

repositories {
	mavenCentral()
}

java {
	sourceCompatibility = JavaVersion.VERSION_11
	targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
	// JADX Core API - use compileOnly to avoid bundling it in the plugin
	// Update this version to match the JADX version you want to support
	compileOnly("io.github.skylot:jadx-core:1.5.2")
	
	// SLF4J API for logging
	compileOnly("org.slf4j:slf4j-api:2.0.17")
	
	// JetBrains annotations
	compileOnly("org.jetbrains:annotations:26.0.2")
}

tasks {
	compileJava {
		options.encoding = "UTF-8"
	}
	
	jar {
		manifest {
			attributes(
				"Plugin-Id" to "magic-strings",
				"Plugin-Name" to "Magic Strings",
				"Plugin-Version" to version
			)
		}
	}
}

