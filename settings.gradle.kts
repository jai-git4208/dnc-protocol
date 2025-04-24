//(c) Ivelosi Technologies. ALl Rights Reserved.
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {

            // Add Safe Args plugin
            plugin("navigation-safeargs", "androidx.navigation.safeargs.kotlin").version("2.7.5")
        }
    }
}


rootProject.name = "dncprotocol"
include(":app")
