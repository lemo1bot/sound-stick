package com.foriad.soundstick

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

/**
 * Loads and shows interstitial ads with calm timing: never during the first
 * 90 seconds of the app session and at most once every 5 minutes.
 */
object InterstitialHelper {
    private var appStartMs = SystemClock.elapsedRealtime()
    private var lastShownMs = 0L
    private var ad: InterstitialAd? = null
    private var loading = false

    fun init(context: Context) {
        appStartMs = SystemClock.elapsedRealtime()
        load(context)
    }

    private fun load(context: Context) {
        if (loading || ad != null) return
        loading = true
        InterstitialAd.load(
            context.applicationContext,
            AdsConfig.INTERSTITIAL_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(loaded: InterstitialAd) {
                    ad = loaded
                    loading = false
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    ad = null
                    loading = false
                }
            }
        )
    }

    /**
     * Shows an interstitial if the timing rules allow it, then runs [after].
     * If no ad may be shown right now, [after] runs immediately.
     */
    fun maybeShow(activity: Activity, after: () -> Unit) {
        val now = SystemClock.elapsedRealtime()
        val ready = ad
        val timingOk = now - appStartMs >= AdsConfig.INTERSTITIAL_FIRST_DELAY_MS &&
            now - lastShownMs >= AdsConfig.INTERSTITIAL_MIN_INTERVAL_MS
        if (ready == null || !timingOk) {
            if (ready == null) load(activity)
            after()
            return
        }
        lastShownMs = now
        ad = null
        ready.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                load(activity)
                after()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                load(activity)
                after()
            }
        }
        ready.show(activity)
    }
}
