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
    const val SHUFFLE_ALL = "Shuffle all songs"
    fun shuffleNow(title: String): String = "Shuffle · $title"
    const val MUTE = "Mute the music and the voice"
    const val UNMUTE = "Turn the music and the voice back on"
    const val BACK = "Back"
    const val START = "start"
    const val START_POSITION = "start position"
    const val READY = "ready"
    const val FILLER_SUFFIX = " — triple step filler"
    const val DONE = "Done!"
    const val NOTHING_TO_SHOW = "nothing to show"
    const val LOADING_MUSIC = "Loading the music…"   // the web front only: a song that has not arrived yet

    // the heart page (the user's own words, 2026-10-28, with the spelling put right)
    const val HEART_NOTE = "Hi, I'm Lior. I made this app for the community, with the intent to help. Nothing would make me happier than getting a genuine, thoughtful email about how someone used and appreciates this app.\n\nPlease describe with complete genuineness and honesty what about this app you liked, and if it made you feel a connection with me, its creator."
    const val EMAIL_ME = "Email me"
    const val CLOSE = "Close"

    // settings
    const val SET_OVERLAY = "Snare and hi-hat over the music"
    const val SET_SNARE = "Snare (between songs, and over the music when that is on)"
    const val SET_HIHAT = "Hi-hat (between songs, and over the music when that is on)"
    const val SET_VOICE = "Read the annotations aloud"
    const val SET_MUTE_NOTE = "The mute button on the dance floor silences everything at once: the music, the drums and the voice."
    fun about(version: String): String = "6SteP $version · font: Inter (SIL Open Font License) · icons: Lucide (ISC License)"

    /** how a phrase is said: words the voices read wrongly are spelled the way they sound. "lead" alone reads as the
     *  metal ("led"); "leed" reads as in "lead and follow" (user 2026-10-19). The screen keeps the real spelling. */
    fun spoken(text: String): String = text.replace(Regex("\\blead\\b", RegexOption.IGNORE_CASE), "leed")
}
