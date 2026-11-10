package com.nova.browser.core

import org.junit.Assert.*
import org.junit.Test

class FilenameSanitizerTest {
    private fun s(n: String?, m: String? = null) = FilenameSanitizer.sanitize(n, m)

    @Test fun plainNameUnchanged() = assertEquals("video.mp4", s("video.mp4"))
    @Test fun pathTraversalStripped() { val r = s("../../etc/passwd"); assertEquals("passwd", r); assertFalse(r.contains("/")) }
    @Test fun windowsTraversalStripped() = assertEquals("evil.exe", s("..\\..\\evil.exe"))
    @Test fun dotDotCollapsed() { val r = s("a..b..c.txt"); assertFalse(r.contains("..")) }
    @Test fun onlyDotsFallsBack() = assertEquals("download", s("..."))
    @Test fun leadingDotRemoved() = assertEquals("hidden", s(".hidden"))
    @Test fun nullBecomesDownload() = assertEquals("download", s(null))
    @Test fun emptyBecomesDownload() = assertEquals("download", s(""))
    @Test fun illegalCharsReplaced() = assertEquals("a_b_c_d.txt", s("a:b*c?d.txt"))
    @Test fun controlCharsReplaced() = assertEquals("a_b.txt", s("a\u0000b.txt"))
    @Test fun nullByteExtensionTrick() = assertFalse(s("shell.php\u0000.jpg").contains('\u0000'))
    @Test fun reservedWindowsName() = assertEquals("_con.txt", s("con.txt"))
    @Test fun extensionFromMimeWhenMissing() = assertEquals("clip.mp4", s("clip", "video/mp4"))
    @Test fun mimeParametersIgnored() = assertEquals("a.pdf", s("a", "application/pdf; charset=binary"))
    @Test fun lengthBounded() { val r = s("x".repeat(500) + ".mp4"); assertTrue(r.length <= 120); assertTrue(r.endsWith(".mp4")) }
    @Test fun absurdExtensionFolded() { val r = s("a." + "z".repeat(40)); assertTrue(r.length <= 120); assertFalse(r.contains("..")) }
    @Test fun uniquifyAddsCounter() = assertEquals("a (1).mp4", FilenameSanitizer.uniquify("a.mp4", setOf("a.mp4")))
    @Test fun uniquifyIncrements() = assertEquals("a (2).mp4", FilenameSanitizer.uniquify("a.mp4", setOf("a.mp4", "a (1).mp4")))
    @Test fun uniquifyCaseInsensitive() = assertEquals("A (1).mp4", FilenameSanitizer.uniquify("A.mp4", setOf("a.MP4")))
    @Test fun uniquifyNoCollision() = assertEquals("b.mp4", FilenameSanitizer.uniquify("b.mp4", setOf("a.mp4")))
    @Test fun mediaMimeValidation() {
        assertTrue(FilenameSanitizer.isAcceptableMedia("video/mp4"))
        assertTrue(FilenameSanitizer.isAcceptableMedia("audio/mpeg; x=y"))
        assertFalse(FilenameSanitizer.isAcceptableMedia("text/html"))
        assertFalse(FilenameSanitizer.isAcceptableMedia(null))
    }
    @Test fun neverContainsSeparatorsFuzz() {
        val rnd = java.util.Random(7)
        val alphabet = "ab./\\:..\u0000 *?<>|~"
        repeat(2000) {
            val raw = (1..rnd.nextInt(30)).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
            val r = FilenameSanitizer.sanitize(raw)
            assertTrue(r, r.isNotEmpty() && !r.contains('/') && !r.contains('\\') && !r.contains("..") && !r.startsWith("."))
        }
    }
}
