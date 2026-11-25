package org.lior.sixstep

/** The app's words, for the shared play logic and the web front. The Android app's own screens still take theirs from
 *  app/src/main/res/values/strings.xml; TextsTest checks that the two say the same. */
object Texts {
    const val APP_NAME = "6SteP"

    // the launcher
    const val LOGO = "6SteP!"
    const val DAILY_EXERCISE = "Daily Exercise"
    const val EXPLORE = "Explore"
    fun minutes(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')} min"
    const val HEART = "Say thanks"
    const val SETTINGS = "Settings"
    const val HELP = "What is what"
    const val SHARE = "Share"
    /** the web page's own address: the share page shows it as a QR code (tools/make_qr.py makes the code from this line)
     *  and as a link that copies itself when tapped (user 2026-11-25: "I want this app to be easy for people to share
     *  between one another") */
    const val WEB_APP_URL = "https://lioravrahami.github.io/6SteP/"
    const val QR_CODE = "A QR code of the 6SteP web page"
    const val SHARE_TITLE = "Share this app with this QR code!"   // above the code (user 2026-11-25)
    const val LINK_COPIED = "Link copied"
    const val QUICK_RECAP = "Quick recap"
    const val HELP_DAILY = "Click this to start a daily exercise! Three minutes a day is the optimal way to create muscle memory!"
    const val HELP_EXPLORE = "Click this if you need a refresher on a specific element that you are confused about."

    // explore
    const val ELEMENTS = "Elements"
    const val COMPOSITIONS = "Compositions"
    const val BUILDING_BLOCKS = "Building Blocks"
    const val LEFT_FREE = "left foot free"
    const val RIGHT_FREE = "right foot free"
    const val LEFT_FREE_SHORT = "left free"
    const val RIGHT_FREE_SHORT = "right free"
    const val CHOOSE_VERSION = "Choose the version"

    // the dance floor
    const val PLAY = "Play"
    const val PAUSE = "Pause"
    const val TEMPO = "Tempo"
    fun bpm(n: Int): String = "$n BPM"
    fun bpmOriginal(n: Int): String = "$n BPM · original"
    const val SONG = "Song"
    const val PLAY_ALL = "Play all"
    const val SHUFFLE_PLAY = "Shuffle play"
    const val UPLOAD = "Upload…"
    fun shuffleNow(title: String): String = "Shuffle · $title"
    const val MUTE = "Mute the music and the voice"
    const val UNMUTE = "Turn the music and the voice back on"
    const val BACK = "Back"
    const val START = "start"
    const val START_POSITION = "start position"
    const val READY = "ready"
    const val FILLER_SUFFIX = " — triple step filler"
    const val LEAD_IN_SUFFIX = " — lead-in"   // the two triple steps on the spot before a part that a swipe went to
    /** the floor's title during those triple steps: what is danced, and the part it leads to, so that the screen says
     *  where a swipe went from its first moment ("triple step — lead-in to send out and bring back") */
    fun leadIn(element: String, part: String): String = element + LEAD_IN_SUFFIX + (if (part.isEmpty()) "" else " to $part")
    const val DONE = "Done!"
    const val NOTHING_TO_SHOW = "nothing to show"
    const val LOADING_MUSIC = "Loading the music…"   // the web front only: a song that has not arrived yet
    const val FULL_SCREEN = "Full screen"            // the web front only
    const val LEAVE_FULL_SCREEN = "Leave the full screen"

    // the heart page (the user's own words, 2026-10-28, with the spelling put right; its second paragraph, which asked
    // what the reader liked about the app, was taken out by the user on 2026-11-21)
    const val HEART_NOTE = "Hi, I'm Lior. I made this app for the community, with the intent to help. Nothing would make me happier than getting a genuine, thoughtful email about how someone used and appreciates this app."
    /** the heart page's website and beer buttons: out for now (user 2026-11-21: "remove the buy me a beer button and
     *  the link to my website button for now"). True brings them back, in the Android app and on the web page. */
    const val HEART_MORE = false
    const val HEART_BEER = "Also, putting apps in the Play Store costs me money. If you feel generous, you can buy me a beer (IRL or via the app)."
    const val EMAIL_ME = "Email me"
    const val MY_WEBSITE = "My website"
    const val BUY_BEER = "Buy me a Beer"
    const val EMAIL = "lior.2pi+6step@gmail.com"   // the app's own address at the user's mailbox (user 2026-10-29)
    const val CLOSE = "Close"
    const val LINK_NOT_SET = "This link is not set up yet"
    /** the user's own pages, still to be given: until then a tap says the link is not set up yet */
    const val WEBSITE = ""
    const val PAY_URL = ""          // with %d for the amount in dollars
    val BEER_AMOUNTS = intArrayOf(5, 15, 30)

    // the window in which the listener adds a song of their own (the web front, for now)
    const val UPLOAD_TITLE = "Upload a song"
    const val UPLOAD_WORDS = "6SteP knows the steps to these songs, but cannot bring their music. If you have one of them as a file on this device, choose it here and dance to it. The file stays on this device."
    const val CHOOSE_FILE = "Choose a file…"
    fun listening(percent: Int): String = "Listening to the song… $percent%"
    fun songAdded(title: String): String = "This is $title. It is one of your songs now."
    fun songForNow(title: String): String = "This is $title. This device would not keep the file, so it is one of your songs until the page is closed."
    const val SONG_UNKNOWN = "6SteP does not know the steps to this recording yet. Send the song to $EMAIL and ask for it to be mapped."
    fun songOtherVersion(title: String): String = "This sounds like $title, but it is not the recording that 6SteP knows: another version, or one that is cut differently. Send the song to $EMAIL and ask for it to be mapped."
    const val WRITE_EMAIL = "Write the email"
    /** the email's words, as the user gave them (2026-11-19) */
    fun mapRequest(name: String): String = "hi, please map out the song named $name for me, thanks!"
    const val SONG_UNREADABLE = "This file could not be read as music."
    const val SONG_NOT_KEPT = "The song could not be kept on this device."
    const val ADDED = "added"

    // settings
    const val SET_OVERLAY = "Snare and hi-hat over the music"
    const val SET_SNARE = "Snare (between songs, and over the music when that is on)"
    const val SET_HIHAT = "Hi-hat (between songs, and over the music when that is on)"
    const val SET_VOICE = "Read the annotations aloud"
    const val SET_MUTE_NOTE = "The mute button on the dance floor silences everything at once: the music, the drums and the voice."
    // the switch for the lead-in (user 2026-11-21: "make this feature toggleable in the settings"); its words are the maintainer's
    const val SET_LEAD_IN = "After a swipe while playing: two triple steps in place before the part"
    // the button that puts the dance floor's hints' flags down again (user 2026-11-21: "add a button in the settings to reset the hints")
    const val SET_HINTS_AGAIN = "Show the dance floor's hints again"
    const val HINTS_AGAIN_DONE = "The hints will show again"
    fun about(version: String): String = "6SteP $version · font: Inter (SIL Open Font License) · icons: Lucide (ISC License)"

    private val LEAD = Regex("\\blead\\b", RegexOption.IGNORE_CASE)
    private val YOYO = Regex("\\byo[ -]?yo(s?)\\b", RegexOption.IGNORE_CASE)

    /** how a phrase is said: words the voices read wrongly are spelled the way they sound. "lead" alone reads as the
     *  metal ("led"); "leed" reads as in "lead and follow" (user 2026-10-19). "yoyo" in one word is read as something
     *  else; "yo-yo", the toy's own spelling, is read right (user 2026-11-21). The screen keeps the real spelling. */
    fun spoken(text: String): String = text.replace(LEAD, "leed").replace(YOYO) { m -> "yo-yo" + m.groupValues[1] }
}
