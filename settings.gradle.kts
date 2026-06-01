pluginManagement {
    repositories {
        val googleMirror = System.getenv("GOOGLE_MAVEN_MIRROR_URL")?.trim().orEmpty()
        val mavenCentralMirror = System.getenv("MAVEN_CENTRAL_MIRROR_URL")?.trim().orEmpty()
        val gradlePluginPortalMirror = System.getenv("GRADLE_PLUGIN_PORTAL_MIRROR_URL")?.trim().orEmpty()

        if (googleMirror.isNotEmpty()) {
            maven(url = googleMirror)
        } else {
            google()
        }

        if (mavenCentralMirror.isNotEmpty()) {
            maven(url = mavenCentralMirror)
        } else {
            mavenCentral()
        }

        if (gradlePluginPortalMirror.isNotEmpty()) {
            maven(url = gradlePluginPortalMirror)
        } else {
            gradlePluginPortal()
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val googleMirror = System.getenv("GOOGLE_MAVEN_MIRROR_URL")?.trim().orEmpty()
        val mavenCentralMirror = System.getenv("MAVEN_CENTRAL_MIRROR_URL")?.trim().orEmpty()

        if (googleMirror.isNotEmpty()) {
            maven(url = googleMirror)
        } else {
            google()
        }

        if (mavenCentralMirror.isNotEmpty()) {
            maven(url = mavenCentralMirror)
        } else {
            mavenCentral()
        }
    }
}

rootProject.name = "PODDeliveryValidator"
include(":app")
