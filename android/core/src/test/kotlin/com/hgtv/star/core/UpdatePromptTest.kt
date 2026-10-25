package com.hgtv.star.core

import org.junit.Assert.*
import org.junit.Test

class UpdatePromptTest {

    @Test fun `by default the update prompt is shown`() {
        val prompt = UpdatePrompt()
        assertFalse(prompt.suppressed(installedCode = 24))
        assertFalse(prompt.suppressed(installedCode = 25))
    }

    @Test fun `declining suppresses the prompt only for the installed version`() {
        val declined = UpdatePrompt().decline(installedCode = 24)
        assertTrue("同一版本不再提示", declined.suppressed(24))
        assertFalse("换了版本就该恢复提示", declined.suppressed(25))
    }

    @Test fun `installing a newer version restores the prompt`() {
        // 24 上点了「暂不更新」，之后手动装上了 25 —— 提示应当恢复
        val declined = UpdatePrompt().decline(installedCode = 24)
        val afterUpgrade = declined.refresh(installedCode = 25)
        assertFalse(afterUpgrade.declined)
        assertFalse(afterUpgrade.suppressed(25))
        assertFalse("恢复之后对新版本也照常提示", afterUpgrade.suppressed(26))
    }

    @Test fun `refresh keeps the decision while the version is unchanged`() {
        val declined = UpdatePrompt().decline(installedCode = 24)
        val sameVersion = declined.refresh(installedCode = 24)
        assertTrue("没换版本就继续安静", sameVersion.suppressed(24))
        assertEquals(declined, sameVersion)
    }

    @Test fun `refresh on a fresh state is a no-op`() {
        assertEquals(UpdatePrompt(), UpdatePrompt().refresh(installedCode = 24))
    }
}
