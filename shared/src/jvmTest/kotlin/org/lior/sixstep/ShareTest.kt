package org.lior.sixstep

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** The share page's QR code (user 2026-11-25) is made by tools/make_qr.py from Texts.WEB_APP_URL and kept in both
 *  fronts' files: each must be of the address Texts gives (run the script again when it changes). That the codes read
 *  back as the address was checked with a QR reader when they were made, and on the web page itself. */
class ShareTest {
    @Test fun theQrCodesAreOfTheWebPagesAddress() {
        assertTrue(Texts.WEB_APP_URL.startsWith("https://") && Texts.WEB_APP_URL.endsWith("/"), Texts.WEB_APP_URL)
        for (path in listOf("../web/src/jsMain/resources/share_qr.svg", "../app/src/main/res/drawable/share_qr.xml")) {
            val text = File(path).readText()
            assertTrue(text.contains("QR code of ${Texts.WEB_APP_URL} "), "$path is not the QR code of ${Texts.WEB_APP_URL}: run tools/make_qr.py")
        }
    }
}
