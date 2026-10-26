// Measures microphone foreground eligibility above keyguard without exposing hosted content or capturing audio.
package com.fghbuild.gomode.voice

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import com.fghbuild.gomode.R

// This private debug surface intentionally reproduces an unsafe pending-cancel handover.
// Its service is disabled by default; no auth, transcripts, hosted content, or recorder loads.
class CarDialLaunchProbeActivity : Activity() {
    private val foreground by lazy { VoiceForegroundLease(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContentView(TextView(this).apply { setText(R.string.car_dial_launch_probe) })
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            foreground.start(getString(R.string.car_dial_launch_probe))
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        foreground.stop()
        super.onDestroy()
    }
}
