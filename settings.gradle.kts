pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { mavenCentral() } }
rootProject.name = "minecraft-modsync"
include("modsync-domain", "modsync-protocol", "modsync-modrinth", "modsync-scanner", "modsync-resolver", "modsync-agent", "modsync-client", "modsync-client-tui", "modsync-server", "modsync-test-fixtures")
