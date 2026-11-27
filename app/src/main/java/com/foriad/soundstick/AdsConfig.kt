package com.foriad.soundstick

/**
 * AdMob ad unit configuration for SoundStick.
 */
object AdsConfig {
    // Banner ad unit
    const val BANNER_ID = "ca-app-pub-8301774269044238/6299148487"

    // Interstitial ad unit
    const val INTERSTITIAL_ID = "ca-app-pub-8301774269044238/4550631746"

    // Calm interstitial timing: none in the first 90 seconds after the app
    // opens, and at most one every 5 minutes.
    const val INTERSTITIAL_FIRST_DELAY_MS = 90 * 1000L
    const val INTERSTITIAL_MIN_INTERVAL_MS = 5 * 60 * 1000L
}
