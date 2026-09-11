package com.exo.musicplayer.desktop

/**
 * The running build's version.
 *
 * Set by the launcher as -Dresonate.version, which the Gradle build fills from
 * the single `resonateVersion` property that also becomes the MSI
 * ProductVersion. So whatever this shows is what Windows has recorded for the
 * installed product, and "did the upgrade take?" is answerable from the About
 * panel instead of from Add/Remove Programs.
 *
 * Falls back to "dev" when the property is absent, which is the case when the
 * app is started straight from a classpath rather than through the launcher.
 */
object AppVersion {
    val name: String = System.getProperty("resonate.version")?.takeIf { it.isNotBlank() } ?: "dev"
}
