package com.myra.assistant.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.myra.assistant.R
import com.myra.assistant.util.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MYRA home tab — two themes:
 *
 * • Minimal (designs 11/12/13/14): full-screen girl art, ONE electric circle
 *   at knee height with OFF/LISTEN beneath it. Nothing else on screen.
 *   Long-press the circle for Chat / Tools / Profile (the nav bar is hidden
 *   on this screen).
 *   - Designs 11/12/13: still art with a slow "breathing" scale + drifting
 *     smoke overlay.
 *   - Design 14: Fazil's animated clip (R.raw.home_bg_14) loops full-screen
 *     as the background — hair/eyes/smoke move inside the video itself, so
 *     the native breathing + smoke stay OFF. The no-circle still is the
 *     poster shown until the first frame renders, and the fallback if
 *     playback ever fails. The circle is NEVER baked into the art/video;
 *     it is always the separate rotating button view on top.
 *
 * • Dashboard (design 2): girl art, greeting card, 4 quick-action chips,
 *   mic circle. The activity bottom nav stays visible for this theme.
 */
class HomeFragment : Fragment() {

    private lateinit var vm: MainViewModel
    private var boundDesign = ""
    private var isDashboard = false

    private var girlArtFull: ImageView? = null
    private var bgVideo: TextureView? = null
    private var smokeView: SmokeDriftView? = null
    private var electricCircle: ElectricCircleView? = null
    private var micStateText: TextView? = null
    private var lastErrorShown: String? = null
    private var breathSet: AnimatorSet? = null

    // Design-14 background video state.
    private var isVideoTheme = false
    private var mediaPlayer: MediaPlayer? = null
    private var playerReady = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        isDashboard = Prefs.homeDesign == "2"
        val layout =
            if (isDashboard) R.layout.fragment_home_dashboard else R.layout.fragment_home
        return inflater.inflate(layout, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        boundDesign = Prefs.homeDesign
        vm = ViewModelProvider(requireActivity())[MainViewModel::class.java]
        if (isDashboard) bindDashboard(view) else bindMinimal(view)

        vm.isConnected.observe(viewLifecycleOwner) { setListening(it == true) }
        // Surface session errors so a failed start is never silent.
        vm.statusText.observe(viewLifecycleOwner) { s ->
            if (s == null || s == lastErrorShown) return@observe
            val low = s.lowercase()
            if (low.contains("error") || low.contains("off hai") || low.contains("lost")) {
                lastErrorShown = s
                Toast.makeText(requireContext(), s, Toast.LENGTH_LONG).show()
            }
        }
        setListening(vm.isConnected.value == true)
    }

    override fun onResume() {
        super.onResume()
        // Design changed in Settings while we were away → rebuild home.
        if (Prefs.homeDesign != boundDesign) {
            (activity as? MainActivity)?.selectTab("home")
            return
        }
        if (isVideoTheme) {
            // Video provides all the motion; just resume playback.
            if (playerReady) mediaPlayer?.start()
        } else {
            smokeView?.start()
        }
        if (!isDashboard && !isVideoTheme && !Prefs.reducedMotion) startBreathing()
    }

    override fun onPause() {
        // Don't drain battery: pause the background video off-screen.
        mediaPlayer?.let { if (it.isPlaying) it.pause() }
        stopBreathing()
        smokeView?.stop()
        super.onPause()
    }

    // ---------- minimal theme ----------

    /** art drawable, circle glow tint, smoke tint per design. */
    private fun themeSpec(key: String): Triple<Int, Int, Int> = when (key) {
        "11" -> Triple(R.drawable.home_design_11, 0xFFFF4D5E.toInt(), 0xFFFF4D5E.toInt())
        "12" -> Triple(R.drawable.home_design_12, 0xFF3DFF88.toInt(), 0xFF2ECC71.toInt())
        "13" -> Triple(R.drawable.home_design_13, 0xFFFFB340.toInt(), 0xFFFF9E40.toInt())
        else -> Triple(R.drawable.home_design_14, 0xFFF04FD8.toInt(), 0xFF9B5CF6.toInt())
    }

    private fun bindMinimal(view: View) {
        val act = activity as? MainActivity
        girlArtFull = view.findViewById(R.id.girlArtFull)
        bgVideo = view.findViewById(R.id.bgVideo)
        smokeView = view.findViewById(R.id.smokeView)
        electricCircle = view.findViewById(R.id.electricCircle)
        micStateText = view.findViewById(R.id.micStateText)

        val (art, glow, smoke) = themeSpec(Prefs.homeDesign)
        girlArtFull?.setImageResource(art)
        electricCircle?.glowTint = glow
        smokeView?.smokeColor = smoke

        isVideoTheme = Prefs.homeDesign == "14"
        if (isVideoTheme) {
            // The clip carries the motion: no smoke overlay on top of it.
            // girlArtFull (set above) stays visible as poster/fallback.
            smokeView?.visibility = View.GONE
            bgVideo?.visibility = View.VISIBLE
            bgVideo?.surfaceTextureListener = videoSurfaceListener
        } else {
            bgVideo?.visibility = View.GONE
        }

        // Pivot at center so the "breathing" scale feels natural.
        girlArtFull?.post {
            val v = girlArtFull ?: return@post
            v.pivotX = v.width / 2f
            v.pivotY = v.height / 2f
        }

        electricCircle?.setOnClickListener { act?.toggleVoiceSession() }
        // No nav bar on this screen — long-press the circle to reach the rest.
        electricCircle?.setOnLongClickListener { showNavMenu(); true }
    }

    private fun showNavMenu() {
        val act = activity as? MainActivity ?: return
        val labels = arrayOf("💬 Chat", "🧰 Tools", "👤 Profile")
        val tabs = arrayOf("chat", "tools", "profile")
        AlertDialog.Builder(requireContext())
            .setTitle("MYRA")
            .setItems(labels) { _, which -> act.selectTab(tabs[which]) }
            .show()
    }

    // ---------- design-14 background video ----------

    private val videoSurfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(
            surface: SurfaceTexture, width: Int, height: Int
        ) {
            setupVideoPlayer(Surface(surface))
        }

        override fun onSurfaceTextureSizeChanged(
            surface: SurfaceTexture, width: Int, height: Int
        ) {
            applyVideoCrop()
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            releaseVideoPlayer()
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private fun setupVideoPlayer(surface: Surface) {
        releaseVideoPlayer()
        val ctx = context ?: return
        try {
            val mp = MediaPlayer()
            mediaPlayer = mp
            mp.setSurface(surface)
            mp.isLooping = true
            mp.setVolume(0f, 0f) // muted — the clip has no audio track anyway
            mp.setDataSource(
                ctx,
                Uri.parse("android.resource://${ctx.packageName}/${R.raw.home_bg_14}")
            )
            mp.setOnPreparedListener { player ->
                playerReady = true
                applyVideoCrop()
                if (isResumed) player.start()
            }
            mp.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    // First real frame is on screen — drop the poster still.
                    girlArtFull?.visibility = View.GONE
                }
                false
            }
            mp.setOnErrorListener { _, _, _ ->
                showPosterFallback()
                true
            }
            mp.prepareAsync()
        } catch (_: Exception) {
            showPosterFallback()
        }
    }

    /** Center-crop the video inside the TextureView so it fills the screen like the still art. */
    private fun applyVideoCrop() {
        val view = bgVideo ?: return
        val mp = mediaPlayer ?: return
        val vw = mp.videoWidth
        val vh = mp.videoHeight
        if (vw <= 0 || vh <= 0 || view.width == 0 || view.height == 0) return
        val videoRatio = vw.toFloat() / vh.toFloat()
        val viewRatio = view.width.toFloat() / view.height.toFloat()
        var scaleX = 1f
        var scaleY = 1f
        if (videoRatio > viewRatio) {
            scaleX = videoRatio / viewRatio
        } else {
            scaleY = viewRatio / videoRatio
        }
        val matrix = Matrix()
        matrix.setScale(scaleX, scaleY, view.width / 2f, view.height / 2f)
        view.setTransform(matrix)
    }

    /** Playback failed → fall back to the no-circle still, exactly like the old static theme. */
    private fun showPosterFallback() {
        releaseVideoPlayer()
        bgVideo?.visibility = View.GONE
        girlArtFull?.visibility = View.VISIBLE
    }

    private fun releaseVideoPlayer() {
        playerReady = false
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
    }

    /** Slow "breathing": gentle scale 1.00→1.04 plus a slight drift, ~9s cycle. */
    private fun startBreathing() {
        val v = girlArtFull ?: return
        if (breathSet != null) return
        val sx = ObjectAnimator.ofFloat(v, "scaleX", 1f, 1.04f).apply {
            duration = 4500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        val sy = ObjectAnimator.ofFloat(v, "scaleY", 1f, 1.04f).apply {
            duration = 4500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        val drift = ObjectAnimator.ofFloat(v, "translationX", 0f, 14f, 0f, -14f, 0f).apply {
            duration = 18000
            repeatCount = ValueAnimator.INFINITE
        }
        breathSet = AnimatorSet().apply { playTogether(sx, sy, drift); start() }
    }

    private fun stopBreathing() {
        breathSet?.cancel()
        breathSet = null
        girlArtFull?.let {
            it.scaleX = 1f
            it.scaleY = 1f
            it.translationX = 0f
        }
    }

    // ---------- dashboard theme (#2) ----------

    private fun bindDashboard(view: View) {
        val act = activity as? MainActivity
        electricCircle = view.findViewById(R.id.electricCircleDash)
        micStateText = view.findViewById(R.id.micStateTextDash)
        electricCircle?.glowTint = 0xFF35C4FF.toInt()
        electricCircle?.setOnClickListener { act?.toggleVoiceSession() }

        val name = Prefs.userName.ifBlank { "Dost" }
        view.findViewById<TextView>(R.id.dashGreeting)?.text =
            "Hello $name, main MYRA hoon"

        view.findViewById<View>(R.id.chipCall)?.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_DIAL))
            } catch (_: Exception) {
                toast("Dialer nahi khul saka")
            }
        }
        view.findViewById<View>(R.id.chipMessage)?.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("sms:")))
            } catch (_: Exception) {
                toast("SMS app nahi khul saki")
            }
        }
        view.findViewById<View>(R.id.chipYouTube)?.setOnClickListener {
            val i = requireContext().packageManager
                .getLaunchIntentForPackage("com.google.android.youtube")
            if (i != null) startActivity(i) else toast("YouTube app nahi mili")
        }
        view.findViewById<View>(R.id.chipTime)?.setOnClickListener {
            val t = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            toast("Time: $t")
        }
    }

    // ---------- shared ----------

    private fun setListening(listening: Boolean) {
        if (listening) {
            micStateText?.text = "LISTEN"
            micStateText?.setTextColor(0xFF35C4FF.toInt())
            electricCircle?.active = true
        } else {
            micStateText?.text = "OFF"
            micStateText?.setTextColor(0xFF8A97A5.toInt())
            electricCircle?.active = false
        }
    }

    private fun toast(s: String) =
        Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        stopBreathing()
        releaseVideoPlayer()
        smokeView?.stop()
        girlArtFull = null
        bgVideo = null
        smokeView = null
        electricCircle = null
        micStateText = null
        super.onDestroyView()
    }
}
