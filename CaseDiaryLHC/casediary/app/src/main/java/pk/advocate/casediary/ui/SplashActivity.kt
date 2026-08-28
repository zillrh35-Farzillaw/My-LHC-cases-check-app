package pk.advocate.casediary.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import pk.advocate.casediary.databinding.ActivitySplashBinding

/**
 * The branded opening screen shown once per cold start, before [MainActivity].
 * The system SplashScreen API (Android 12+) only supports an icon and a
 * background colour, not the multi-line branding this app wants ("CCMS", its
 * full name, and the developer credit), so this is a small dedicated Activity
 * instead — themed to match the app (see Theme.CaseDiary.Splash) with a brief
 * fade/scale entrance before handing off.
 */
class SplashActivity : AppCompatActivity() {

    private lateinit var b: ActivitySplashBinding
    private val handler = Handler(Looper.getMainLooper())
    private val goToMain = Runnable {
        if (isFinishing) return@Runnable
        startActivity(Intent(this, MainActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(b.root)

        val icon = ObjectAnimator.ofPropertyValuesHolder(
            b.splashIcon,
            PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.7f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.7f, 1f)
        ).apply { duration = 840 }

        val title = ObjectAnimator.ofPropertyValuesHolder(
            b.splashTitle,
            PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 16f, 0f)
        ).apply { duration = 840; startDelay = 240 }

        val subtitle = ObjectAnimator.ofFloat(b.splashSubtitle, View.ALPHA, 0f, 1f)
            .apply { duration = 720; startDelay = 640 }

        val credit = ObjectAnimator.ofFloat(b.splashCredit, View.ALPHA, 0f, 1f)
            .apply { duration = 720; startDelay = 840 }

        AnimatorSet().apply {
            playTogether(icon, title, subtitle, credit)
            start()
        }

        handler.postDelayed(goToMain, SPLASH_DURATION_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(goToMain)
        super.onDestroy()
    }

    companion object {
        private const val SPLASH_DURATION_MS = 3200L
    }
}
