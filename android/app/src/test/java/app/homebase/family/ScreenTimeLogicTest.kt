package app.homebase.family

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The rule for what counts as screen time: active playing or watching only. */
class ScreenTimeLogicTest {
    private val L = ScreenTimeLogic

    @Test fun knownEntertainmentAppsAreClassified() {
        assertEquals("watch", L.classify("com.google.android.youtube", -1))
        assertEquals("play", L.classify("com.roblox.client", -1))
        assertEquals("social", L.classify("com.zhiliaoapp.musically", -1))
        assertEquals("browser", L.classify("com.android.chrome", -1))
    }

    @Test fun theSystemCategoryCatchesGamesWeNeverListed() {
        assertEquals("play", L.classify("com.some.newgame", 0))
        assertEquals("watch", L.classify("com.some.player", 2))
    }

    @Test fun studyWorkAndEverydayAppsAreNeverCounted() {
        assertNull(L.classify("com.google.android.apps.classroom", 6))          // productivity / education
        assertNull(L.classify("com.whatsapp", 7))
        assertNull(L.classify("com.google.android.apps.maps", 8))
        assertNull(L.counted(null, 0, true))                                      // not entertainment: never counted, even with sound
    }

    @Test fun aGameNobodyIsTouchingIsAfk() {
        assertEquals("play", L.counted("play", 30, false))
        assertNull(L.counted("play", 600, false))
        assertNull(L.counted("play", 600, true))                                  // music from an untouched game is still AFK
    }

    @Test fun aVideoCanBeWatchedWithoutTouchingButNotWhilePaused() {
        assertEquals("watch", L.counted("watch", 2000, true))
        assertNull(L.counted("watch", 2000, false))                               // paused and abandoned
        assertEquals("watch", L.counted("watch", 10, false))                      // just tapped it
    }

    @Test fun aFeedOnlyCountsWhileItIsBeingScrolled() {
        assertEquals("social", L.counted("social", 20, false))
        assertNull(L.counted("social", 400, true))
    }

    @Test fun aBrowserCountsOnlyWhenSomethingIsPlaying() {
        assertEquals("watch", L.counted("browser", 5, true))
        assertNull(L.counted("browser", 5, false))                                 // reading homework
    }
}
