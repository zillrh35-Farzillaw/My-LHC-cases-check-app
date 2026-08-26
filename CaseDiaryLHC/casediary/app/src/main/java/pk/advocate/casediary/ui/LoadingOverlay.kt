package pk.advocate.casediary.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.view.View
import pk.advocate.casediary.databinding.LoadingOverlayBinding

/**
 * A small "processing" overlay — a translucent scrim over a card with a
 * spinner, a gently pulsing icon and a status line — shown while a scan or
 * check is running so a wait reads as active work rather than a frozen screen.
 */
class LoadingOverlay(private val binding: LoadingOverlayBinding) {

    private var pulse: ObjectAnimator? = null

    fun show(message: String) {
        binding.loadingText.text = message
        if (binding.loadingRoot.visibility == View.VISIBLE) return
        binding.loadingRoot.alpha = 0f
        binding.loadingRoot.visibility = View.VISIBLE
        binding.loadingRoot.animate().alpha(1f).setDuration(150).start()

        pulse = ObjectAnimator.ofPropertyValuesHolder(
            binding.loadingIcon,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.15f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.15f, 1f)
        ).apply {
            duration = 900
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    fun hide() {
        pulse?.cancel()
        pulse = null
        if (binding.loadingRoot.visibility != View.VISIBLE) return
        binding.loadingRoot.animate().alpha(0f).setDuration(150)
            .withEndAction { binding.loadingRoot.visibility = View.GONE }
            .start()
    }

    /** Stops the pulse animator without scheduling a new fade — for use right
     *  before the underlying view is torn down (e.g. onDestroyView). */
    fun cancel() {
        pulse?.cancel()
        pulse = null
    }
}
