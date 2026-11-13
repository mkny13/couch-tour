package dev.mike.couchtour

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManifestSecurityTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `application disables backup via FLAG_ALLOW_BACKUP`() {
        val appInfo = context.applicationInfo
        val allowsBackup = (appInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP) != 0
        assertFalse(
            "Expected FLAG_ALLOW_BACKUP to be unset in ApplicationInfo, but it was set",
            allowsBackup
        )
    }

    @Test
    fun `application disables cleartext traffic`() {
        val appInfo = context.applicationInfo
        val usesCleartext = (appInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC) != 0
        assertFalse(
            "Expected FLAG_USES_CLEARTEXT_TRAFFIC to be unset in ApplicationInfo, but it was set",
            usesCleartext
        )
    }

    @Test
    fun `exported app components match exactly the three permitted entry points`() {
        val pm = context.packageManager
        val flags = PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS

        val pkgInfo = pm.getPackageInfo(context.packageName, flags)

        val exportedActivities = pkgInfo.activities?.filter { it.exported }?.map { it.name } ?: emptyList()
        val exportedServices = pkgInfo.services?.filter { it.exported }?.map { it.name } ?: emptyList()
        val exportedReceivers = pkgInfo.receivers?.filter { it.exported }?.map { it.name } ?: emptyList()
        val exportedProviders = pkgInfo.providers?.filter { it.exported }?.map { it.name } ?: emptyList()

        val allExported = (exportedActivities + exportedServices + exportedReceivers + exportedProviders).toSet()

        val appExported = allExported
            .filter { it.startsWith("dev.mike.couchtour.") }
            .map { it.removePrefix("dev.mike.couchtour.") }
            .toSet()

        val expectedAppComponents = setOf(
            "MainActivity",
            "TvMainActivity",
            "PlaybackService",
        )

        assertEquals("Exported app components must match expected", expectedAppComponents, appExported)
    }

    @Test
    fun `source manifest declares exactly three components and all three are the expected entry points`() {
        // Resolve AndroidManifest.xml from working dir (app/ or root)
        val manifestFile = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml")
        ).firstOrNull { it.exists() } ?: error("AndroidManifest.xml not found")

        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(manifestFile.readText()))

        val declaredComponents = mutableListOf<Pair<String, Boolean>>() // Pair(name, isExported)
        var eventType = parser.eventType

        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                val tag = parser.name
                if (tag in setOf("activity", "service", "receiver", "provider")) {
                    var name: String? = null
                    var exported: Boolean? = null
                    for (i in 0 until parser.attributeCount) {
                        val attrName = parser.getAttributeName(i)
                        val attrVal = parser.getAttributeValue(i)
                        if (attrName == "name" || attrName == "android:name") {
                            name = attrVal.removePrefix(".")
                        } else if (attrName == "exported" || attrName == "android:exported") {
                            exported = attrVal.toBoolean()
                        }
                    }
                    if (name != null) {
                        declaredComponents.add(name to (exported ?: false))
                    }
                }
            }
            eventType = parser.next()
        }

        val expected = listOf(
            "MainActivity" to true,
            "TvMainActivity" to true,
            "PlaybackService" to true,
        )

        assertEquals("Declared manifest components and export status must match exactly", expected, declaredComponents)
    }

    @Test
    fun `data extraction rules exclude all domains for cloud backup and device transfer`() {
        val parser = context.resources.getXml(R.xml.data_extraction_rules)
        val cloudExcludes = mutableSetOf<String>()
        val deviceTransferExcludes = mutableSetOf<String>()
        var currentSection: String? = null

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "cloud-backup" -> currentSection = "cloud-backup"
                        "device-transfer" -> currentSection = "device-transfer"
                        "exclude" -> {
                            val domain = parser.getAttributeValue(null, "domain")
                            if (currentSection == "cloud-backup" && domain != null) {
                                cloudExcludes.add(domain)
                            } else if (currentSection == "device-transfer" && domain != null) {
                                deviceTransferExcludes.add(domain)
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "cloud-backup" || parser.name == "device-transfer") {
                        currentSection = null
                    }
                }
            }
            eventType = parser.next()
        }

        val requiredDomains = setOf("root", "file", "database", "sharedpref", "external")
        assertEquals("cloud-backup must exclude all storage domains", requiredDomains, cloudExcludes)
        assertEquals("device-transfer must exclude all storage domains", requiredDomains, deviceTransferExcludes)
    }

    @Test
    fun `MainActivity has only MAIN intent filter and does not declare MEDIA_PLAY_FROM_SEARCH`() {
        val manifestFile = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml")
        ).firstOrNull { it.exists() } ?: error("AndroidManifest.xml not found")

        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(manifestFile.readText()))

        var currentActivity: String? = null
        val actionsByActivity = mutableMapOf<String, MutableSet<String>>()
        var eventType = parser.eventType

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "activity" -> {
                            val name = (0 until parser.attributeCount)
                                .firstOrNull { parser.getAttributeName(it) in setOf("name", "android:name") }
                                ?.let { parser.getAttributeValue(it).removePrefix(".") }
                            currentActivity = name
                            if (name != null) actionsByActivity.getOrPut(name) { mutableSetOf() }
                        }
                        "action" -> {
                            val action = (0 until parser.attributeCount)
                                .firstOrNull { parser.getAttributeName(it) in setOf("name", "android:name") }
                                ?.let { parser.getAttributeValue(it) }
                            if (currentActivity != null && action != null) {
                                actionsByActivity[currentActivity]?.add(action)
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "activity") currentActivity = null
                }
            }
            eventType = parser.next()
        }

        val mainActions = actionsByActivity["MainActivity"] ?: emptySet()
        assertEquals(setOf("android.intent.action.MAIN"), mainActions)
        assertFalse(
            "MainActivity must not declare MEDIA_PLAY_FROM_SEARCH",
            mainActions.contains("android.media.action.MEDIA_PLAY_FROM_SEARCH")
        )
    }

    @Test
    fun `PlaybackService intent filter contains expected media session actions`() {
        val manifestFile = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml")
        ).firstOrNull { it.exists() } ?: error("AndroidManifest.xml not found")

        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(manifestFile.readText()))

        var currentService: String? = null
        val actionsByService = mutableMapOf<String, MutableSet<String>>()
        var eventType = parser.eventType

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "service" -> {
                            val name = (0 until parser.attributeCount)
                                .firstOrNull { parser.getAttributeName(it) in setOf("name", "android:name") }
                                ?.let { parser.getAttributeValue(it).removePrefix(".") }
                            currentService = name
                            if (name != null) actionsByService.getOrPut(name) { mutableSetOf() }
                        }
                        "action" -> {
                            val action = (0 until parser.attributeCount)
                                .firstOrNull { parser.getAttributeName(it) in setOf("name", "android:name") }
                                ?.let { parser.getAttributeValue(it) }
                            if (currentService != null && action != null) {
                                actionsByService[currentService]?.add(action)
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "service") currentService = null
                }
            }
            eventType = parser.next()
        }

        val serviceActions = actionsByService["PlaybackService"] ?: emptySet()
        val expectedActions = setOf(
            "androidx.media3.session.MediaSessionService",
            "androidx.media3.session.MediaLibraryService",
            "android.media.action.MEDIA_PLAY_FROM_SEARCH",
        )
        assertEquals(expectedActions, serviceActions)
    }

    @Test
    fun `MainActivity ignores MEDIA_PLAY_FROM_SEARCH and arbitrary query extras without persisting or overriding state`() {
        val searchIntent = android.content.Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH").apply {
            putExtra("query", "https://malicious.example.com/exploit")
            putExtra(android.app.SearchManager.QUERY, "file:///etc/passwd")
        }

        val controller = org.robolectric.Robolectric.buildActivity(
            MainActivity::class.java,
            searchIntent
        ).create()

        controller.newIntent(searchIntent)

        // Verify SyncApi base URL remains the default (no override was applied by query extras)
        assertEquals(
            "https://couch-tour-sync-staging.mkastellec.workers.dev",
            SyncApi.baseUrl.toString().trimEnd('/')
        )
    }
}
