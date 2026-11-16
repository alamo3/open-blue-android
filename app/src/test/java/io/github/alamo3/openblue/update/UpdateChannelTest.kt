package io.github.alamo3.openblue.update

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateChannelTest {
    @Test fun releasesAreIsolatedByChannel() {
        assertTrue(UpdateChannel.PUBLIC.baseUrl.endsWith("/releases/download/latest/"))
        assertTrue(UpdateChannel.STAGING.baseUrl.endsWith("/releases/download/staging-changes/"))
        assertEquals(UpdateChannel.STAGING, UpdateChannel.fromKey("staging-changes"))
        assertEquals(UpdateChannel.PUBLIC, UpdateChannel.fromKey(null))
        assertEquals(UpdateChannel.PUBLIC, UpdateChannel.fromKey("unexpected"))
    }

    @Test fun checkedReleaseKeepsItsSourceForDownload() {
        val metadata = """{"versionCode":999999,"versionName":"0.1.999999-staging"}"""
        val release = Json.decodeFromString(Release.serializer(), metadata).copy(channel = UpdateChannel.STAGING)
        assertEquals(UpdateChannel.STAGING, release.channel)
        assertTrue(release.isNewer)
        assertFalse(release.channel.baseUrl == UpdateChannel.PUBLIC.baseUrl)
    }
}
