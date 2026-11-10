package com.nova.browser.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ResourceTypeGuesserTest {
    @Test fun mainFrameIsDocument() = assertEquals(ResourceType.DOCUMENT, ResourceTypeGuesser.guess("https://a.com/x.js", true, null))
    @Test fun scriptByExtension() = assertEquals(ResourceType.SCRIPT, ResourceTypeGuesser.guess("https://a.com/x.js?v=1", false, null))
    @Test fun imageByAccept() = assertEquals(ResourceType.IMAGE, ResourceTypeGuesser.guess("https://a.com/pixel", false, "image/avif,image/webp,*/*"))
    @Test fun cssByExtension() = assertEquals(ResourceType.STYLESHEET, ResourceTypeGuesser.guess("https://a.com/s.css", false, null))
    @Test fun unknownIsOther() = assertEquals(ResourceType.OTHER, ResourceTypeGuesser.guess("https://a.com/x", false, null))
}
