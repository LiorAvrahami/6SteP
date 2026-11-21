package org.lior.sixstep

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/** The shared words (Texts, for the shared play logic and the web front) against the Android app's own
 *  (res/values/strings.xml): where both have a text, they must say the same. (What the dance floor itself says is
 *  only in Texts since 2026-11-21: the Android app's floor is the shared one.) */
class TextsTest {
    private val xml = File("../app/src/main/res/values/strings.xml").readText()

    private fun android(name: String): String {
        val m = Regex("<string name=\"$name\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL).find(xml) ?: fail("strings.xml has no $name")
        var s = m.groupValues[1]
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length - 1)   // quoted to keep its spaces
        return s.replace("\\'", "'").replace("\\n", "\n")
    }

    @Test fun theSameWords() {
        val pairs = mapOf(
            "app_name" to Texts.APP_NAME, "logo" to Texts.LOGO, "daily_exercise" to Texts.DAILY_EXERCISE, "explore" to Texts.EXPLORE,
            "heart" to Texts.HEART, "settings" to Texts.SETTINGS, "help" to Texts.HELP, "quick_recap" to Texts.QUICK_RECAP,
            "help_daily" to Texts.HELP_DAILY, "help_explore" to Texts.HELP_EXPLORE,
            "elements" to Texts.ELEMENTS, "compositions" to Texts.COMPOSITIONS, "building_blocks" to Texts.BUILDING_BLOCKS,
            "left_free" to Texts.LEFT_FREE, "right_free" to Texts.RIGHT_FREE, "left_free_short" to Texts.LEFT_FREE_SHORT,
            "right_free_short" to Texts.RIGHT_FREE_SHORT, "choose_version" to Texts.CHOOSE_VERSION,
            "play" to Texts.PLAY, "pause" to Texts.PAUSE, "tempo" to Texts.TEMPO, "song" to Texts.SONG,
            "mute" to Texts.MUTE, "unmute" to Texts.UNMUTE, "back" to Texts.BACK, "start" to Texts.START,
            "heart_note" to Texts.HEART_NOTE, "heart_beer" to Texts.HEART_BEER, "email_me" to Texts.EMAIL_ME,
            "my_website" to Texts.MY_WEBSITE, "buy_beer" to Texts.BUY_BEER, "close" to Texts.CLOSE, "link_not_set" to Texts.LINK_NOT_SET,
            "set_overlay" to Texts.SET_OVERLAY, "set_snare" to Texts.SET_SNARE, "set_hihat" to Texts.SET_HIHAT, "set_voice" to Texts.SET_VOICE,
            "set_mute_note" to Texts.SET_MUTE_NOTE, "set_lead_in" to Texts.SET_LEAD_IN,
            // the ones with something filled in
            "minutes" to Texts.minutes(187).replace("3:07", "%1\$d:%2\$02d"), "about" to Texts.about("X").replace("X", "%1\$s"),
        )
        for ((name, shared) in pairs) assertEquals(android(name), shared, "the text \"$name\"")
    }

    @Test fun wordsTheVoicesReadWronglyAreSpelledTheWayTheySound() {
        assertEquals("yo-yo, 3 times", Texts.spoken("yoyo, 3 times"))
        assertEquals("Yo-yo", Texts.spoken("Yoyo").replaceFirstChar { it.uppercaseChar() })
        assertEquals("yo-yo", Texts.spoken("yo-yo"))
        assertEquals("two yo-yos", Texts.spoken("two yoyos"))
        assertEquals("leed underarm pass", Texts.spoken("lead underarm pass"))
        assertEquals("side to side", Texts.spoken("side to side"))
    }
}
