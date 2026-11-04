pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven(url = "https://artifactory.img.ly/artifactory/imgly")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // jcenter is dead, so a few dependencies (Simple-Commons, the VR widgets, imagefilters)
        // are kept here as real files to keep the project buildable offline
        maven { setUrl("${rootDir}/local-maven") }
        google()
        mavenCentral()
        maven { setUrl("https://jitpack.io") }
        maven(url = "https://artifactory.img.ly/artifactory/imgly")
    }
}

rootProject.name = "Simple-Gallery"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
include(":app")

// TODO: This will be deprecated in future. Migrate to the newer `pluginManagement { includeBuild() }` mechanism instead of explicitly substituting dependency.
/*includeBuild("../Simple-Commons") {
    dependencySubstitution {
        substitute(module("com.github.SimpleMobileTools:Simple-Commons")).using(project(":commons"))
    }
}*/
