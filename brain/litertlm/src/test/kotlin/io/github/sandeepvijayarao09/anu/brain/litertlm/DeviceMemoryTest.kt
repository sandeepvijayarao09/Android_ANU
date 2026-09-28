package io.github.sandeepvijayarao09.anu.brain.litertlm

import io.github.sandeepvijayarao09.anu.core.model.BrainId
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceMemoryTest {

    @Test
    fun `a phone's memory is what it was sold with, when Android reports it close to the total`() {
        assertEquals("the Pixel 10 Pro, in binary units", 16, ModelManager.memoryGb(total = 16_331_845_632, advertised = 17_179_869_184))
        assertEquals("a phone that reports decimal units", 16, ModelManager.memoryGb(total = 16_300_000_000, advertised = 16_000_000_000))
        assertEquals("a 12 GB phone that keeps some for itself", 12, ModelManager.memoryGb(total = 11_600_000_000, advertised = 12_000_000_000))
    }

    @Test
    fun `an advertised size far above the total is ignored, as on the 10 GB emulator`() {
        assertEquals(10, ModelManager.memoryGb(total = 10_421_329_920, advertised = 16_000_000_000))
        assertEquals("before Android 14", 10, ModelManager.memoryGb(total = 10_421_329_920, advertised = 0))
    }

    @Test
    fun `models ask for the memory Google's Gallery asks for`() {
        assertEquals(8, ModelCatalog.minMemoryGb(BrainId.BASE))
        assertEquals(12, ModelCatalog.minMemoryGb(BrainId.PRO))
        assertEquals(0, ModelCatalog.minMemoryGb(BrainId.CLOUD_FLASH))
    }
}
