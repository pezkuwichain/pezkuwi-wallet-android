package io.novafoundation.nova.feature_dapp_impl.presentation.main.hub

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView
import coil.ImageLoader
import coil.load
import coil.transform.CircleCropTransformation
import io.novafoundation.nova.common.utils.insets.applyStatusBarInsets
import io.novafoundation.nova.feature_dapp_impl.R

/*
 * The hub's screen, drawn in code so the whole design sits in one place: a deep navy
 * page, the title with the Dijital Kurdistan mark, and one glowing card per section,
 * each a four-column grid of apps. It lives inside the DApps tab, so the wallet's own
 * bottom bar stays in view.
 */
class DksHubView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    interface Handler {
        fun onHubAppClicked(app: DksHubApp)
        fun onHubBackClicked()
    }

    var hubHandler: Handler? = null

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(24))
    }

    private val logo = ImageView(context)

    init {
        // Swallow touches so nothing behind the hub reacts.
        isClickable = true
        isFocusable = true
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#0B1230"), Color.parseColor("#0A0F24"), Color.parseColor("#070B1A"))
        )

        val scroll = NestedScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        content.applyStatusBarInsets(consume = false)

        content.addView(header())
        DksHub.sections.forEach { content.addView(section(it)) }
    }

    fun loadLogo(imageLoader: ImageLoader) {
        logo.load(DksHub.LOGO_URL, imageLoader) {
            transformations(CircleCropTransformation())
            crossfade(true)
        }
    }

    // ─── header ─────────────────────────────────────────────────────────────

    private fun header(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(18))
        }

        val back = ImageView(context).apply {
            setImageResource(R.drawable.ic_arrow_back)
            imageTintList = ColorStateList.valueOf(Color.parseColor("#B9C6E4"))
            contentDescription = context.getString(R.string.dks_hub_back)
            scaleType = ImageView.ScaleType.CENTER
            background = ripple(Color.TRANSPARENT, radius = dp(20).toFloat())
            setOnClickListener { hubHandler?.onHubBackClicked() }
        }
        row.addView(back, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(6) })

        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(
            text(R.string.dks_hub_title, 28f, "#E4EEFF", bold = true).apply {
                // The title's light from the hub's mark: a soft blue glow behind the letters.
                setShadowLayer(dp(10).toFloat(), 0f, 0f, Color.parseColor("#803B82F6"))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        titles.addView(text(R.string.dks_hub_subtitle, 15f, "#8EA1C8"))
        row.addView(titles, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        // The mark, in a ring of the section colours.
        val ring = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(
                    Color.parseColor("#22C55E"),
                    Color.parseColor("#F59E0B"),
                    Color.parseColor("#EF4444"),
                    Color.parseColor("#3B82F6"),
                    Color.parseColor("#22C55E")
                )
                gradientType = GradientDrawable.SWEEP_GRADIENT
            }
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        logo.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#141C3D")) }
        ring.addView(logo, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        row.addView(ring, LinearLayout.LayoutParams(dp(60), dp(60)))
        return row
    }

    // ─── sections ───────────────────────────────────────────────────────────

    private fun section(s: DksHubSection): View {
        val color = s.color.toInt()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(ColorUtils.setAlphaComponent(color, 0x33), ColorUtils.setAlphaComponent(color, 0x14))
            ).apply {
                cornerRadius = dp(20).toFloat()
                setStroke(dp(2), ColorUtils.setAlphaComponent(color, 0xCC))
            }
            clipToOutline = true
            elevation = dp(6).toFloat()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                outlineAmbientShadowColor = color
                outlineSpotShadowColor = color
            }
        }

        // Header band: the section's name in capitals, and how many apps it holds.
        val band = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(ColorUtils.setAlphaComponent(color, 0x59), ColorUtils.setAlphaComponent(color, 0x1F))
            ).apply {
                cornerRadii = floatArrayOf(dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), 0f, 0f, 0f, 0f)
            }
        }
        band.addView(
            TextView(context).apply {
                text = s.emoji
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            },
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
        )
        band.addView(
            TextView(context).apply {
                text = context.getString(s.title).uppercase()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(Color.WHITE)
                typeface = font(bold = true)
                letterSpacing = 0.04f
            },
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        band.addView(
            TextView(context).apply {
                text = resources.getQuantityString(R.plurals.dks_hub_apps_count, s.apps.size, s.apps.size)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.parseColor("#E6ECF7"))
                typeface = font(bold = false)
                setPadding(dp(10), dp(3), dp(10), dp(3))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(ColorUtils.setAlphaComponent(Color.BLACK, 0x40))
                    setStroke(dp(1), ColorUtils.setAlphaComponent(color, 0xB3))
                }
            }
        )
        card.addView(band)

        // A hairline in the section colour under the band.
        card.addView(
            View(context).apply { setBackgroundColor(ColorUtils.setAlphaComponent(color, 0x66)) },
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(1))
        )

        val grid = GridLayout(context).apply {
            columnCount = COLUMNS
            setPadding(dp(6), dp(12), dp(6), dp(10))
        }
        s.apps.forEach { grid.addView(tile(it, color), tileParams()) }
        card.addView(grid)

        val wrapper = FrameLayout(context).apply { setPadding(0, 0, 0, dp(18)) }
        wrapper.addView(card)
        return wrapper
    }

    private fun tile(app: DksHubApp, color: Int): View {
        val tile = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), dp(6), dp(2), dp(8))
            background = ripple(Color.TRANSPARENT, radius = dp(14).toFloat(), rippleColor = ColorUtils.setAlphaComponent(color, 0x55))
            if (app.isAvailable) {
                contentDescription = context.getString(app.title)
                setOnClickListener { hubHandler?.onHubAppClicked(app) }
            } else {
                contentDescription = context.getString(R.string.dks_hub_app_coming_soon, context.getString(app.title))
                alpha = 0.45f
            }
        }
        // The icon sits on a soft disc of the section's colour, lit from its centre.
        val icon = TextView(context).apply {
            text = app.emoji
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(30).toFloat()
                colors = intArrayOf(ColorUtils.setAlphaComponent(color, 0x55), ColorUtils.setAlphaComponent(color, 0x00))
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        tile.addView(icon, LinearLayout.LayoutParams(dp(58), dp(58)))
        tile.addView(
            TextView(context).apply {
                setText(app.title)
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.parseColor("#F1F5FF"))
                typeface = font(bold = true)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), Color.parseColor("#99000000"))
            },
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        )
        return tile
    }

    private fun tileParams() = GridLayout.LayoutParams(
        GridLayout.spec(GridLayout.UNDEFINED),
        GridLayout.spec(GridLayout.UNDEFINED, 1f)
    ).apply {
        width = 0
        height = LayoutParams.WRAP_CONTENT
        setMargins(dp(2), dp(2), dp(2), dp(2))
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private fun text(res: Int, sp: Float, color: String, bold: Boolean = false) = TextView(context).apply {
        setText(res)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(Color.parseColor(color))
        typeface = font(bold)
    }

    private fun font(bold: Boolean): Typeface? =
        ResourcesCompat.getFont(context, if (bold) R.font.public_sans_bold else R.font.public_sans_regular)

    private fun ripple(fill: Int, radius: Float, rippleColor: Int = Color.parseColor("#33FFFFFF")): RippleDrawable {
        val shape = GradientDrawable().apply { cornerRadius = radius; setColor(fill) }
        val mask = GradientDrawable().apply { cornerRadius = radius; setColor(Color.WHITE) }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), shape, mask)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val COLUMNS = 4
    }
}
