package com.fredapp.wbooksutil

import org.junit.Assert.assertEquals
import org.junit.Test

class WearUploadPathTest {
    @Test
    fun encodes_plain_upload_without_query_when_size_unknown() {
        assertEquals(
            "/wbooks/upload/Moby+Dick.epub",
            WatchRepository.WearUploadPath.encode("Moby Dick.epub", totalBytes = -1L, overwrite = false),
        )
    }

    @Test
    fun encodes_size_and_overwrite_flags() {
        assertEquals(
            "/wbooks/upload/Moby+Dick.epub?bytes=812577&overwrite=1",
            WatchRepository.WearUploadPath.encode("Moby Dick.epub", totalBytes = 812_577L, overwrite = true),
        )
    }
}
