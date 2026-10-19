package com.medicabinet.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.medicabinet.app.app.AppLog
import com.medicabinet.app.persistence.AppPaths
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
class PrivacyTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * No system permission — in particular not INTERNET. (AndroidX adds one app-private, signature-level
     * permission named after the package; it grants nothing and is ignored here.)
     */
    @Test
    fun theAppRequestsNoSystemPermissionAtAll() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions?.toList().orEmpty()
        val system = requested.filterNot { it.startsWith("${context.packageName}.") }
        assertTrue(system.isEmpty(), "system permissions requested: $system")
        assertFalse("android.permission.INTERNET" in requested)
    }

    @Test
    fun autoBackupIsOnAndPointsAtTheRules() {
        assertTrue(context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)
    }

    /** The folders each backup section includes, as `domain:path`. */
    private fun includes(resource: Int): Map<String, List<String>> {
        val sections = mutableMapOf<String, MutableList<String>>()
        var section = "root"
        context.resources.getXml(resource).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                if (parser.name == "cloud-backup" || parser.name == "device-transfer") section = parser.name
                if (parser.name == "include") {
                    sections.getOrPut(section) { mutableListOf() } +=
                        "${parser.getAttributeValue(null, "domain")}:${parser.getAttributeValue(null, "path")}"
                }
            }
        }
        return sections
    }

    private val cabinetFiles = listOf("database:cabinet.db", "database:cabinet.db-wal", "file:backups/")

    @Test
    fun legacyBackupRulesIncludeTheDatabaseItsWalFileAndTheBackups() {
        assertEquals(cabinetFiles, includes(R.xml.backup_rules).getValue("root"))
    }

    @Test
    fun cloudBackupAndDeviceTransferIncludeTheSameFiles() {
        val rules = includes(R.xml.data_extraction_rules)
        assertEquals(cabinetFiles, rules.getValue("cloud-backup"))
        assertEquals(cabinetFiles, rules.getValue("device-transfer"))
    }

    @Test
    fun theBackedUpFileNamesMatchWhereTheDatabaseActuallyLives() {
        assertEquals("cabinet.db", AppPaths(context).databaseFile.name)
        assertEquals("backups", AppPaths(context).backupsDirectory.name)
    }

    @Test
    fun logsCarryTheFailureKindAndExceptionClassButNeverTheMessage() {
        ShadowLog.clear()
        AppLog.bootstrapFailed("migration", IllegalStateException("Doliprane lot 12345 serial ABCDEF"))
        val entry = assertNotNull(ShadowLog.getLogs().lastOrNull { it.tag == "MediCabinet" })
        assertEquals(Log.ERROR, entry.type)
        assertContains(entry.msg, "migration")
        assertContains(entry.msg, "IllegalStateException")
        assertFalse(entry.msg.contains("Doliprane") || entry.msg.contains("12345") || entry.msg.contains("ABCDEF"))
    }
}
