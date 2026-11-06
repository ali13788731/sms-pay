package ir.smsforward

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * لوگوی انیمیشنی: حباب پیام خط‌به‌خط کشیده می‌شود، پر می‌شود، فلش انتقال رسم می‌شود
 * و بعد موج‌ها و ذره‌های نورانی دورش می‌چرخند. همه‌چیز با Canvas کشیده می‌شود (بدون کتابخانه).
 * مختصات منطقی ۱۰۰×۱۰۰ است و مرکز حباب (۵۴،۵۵) در وسط view قرار می‌گیرد.
 */
class LogoView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val cx = 54f
    private val cy = 55f

    private val bubble = Path().apply {
        moveTo(38f, 33f); lineTo(70f, 33f); quadTo(78f, 33f, 78f, 41f)
        lineTo(78f, 59f); quadTo(78f, 67f, 70f, 67f)
        lineTo(50f, 67f); lineTo(38f, 77f); lineTo(38f, 67f)
        quadTo(30f, 67f, 30f, 59f); lineTo(30f, 41f); quadTo(30f, 33f, 38f, 33f); close()
    }
    private val arrow = Path().apply {
        moveTo(43f, 50f); lineTo(65f, 50f)
        moveTo(58f, 43f); lineTo(65f, 50f); lineTo(58f, 57f)
    }
    private val bubbleLen = totalLength(bubble)
    private val arrowLen = totalLength(arrow)
    private val piece = Path()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.6f; color = Color.WHITE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.parseColor("#0B6E75")
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 0.9f; color = Color.WHITE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.parseColor("#BFF7FA") }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            cx, cy, 46f,
            intArrayOf(Color.argb(110, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
    }

    private var t = 0f     // پیشرفت ورود، از ۰ تا ۱
    private var loop = 0f  // حلقه‌ی بی‌پایان موج‌ها

    private val intro = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2000
        interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }
    private val looper = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2200
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { loop = it.animatedValue as Float; invalidate() }
    }

    fun start() {
        t = 0f
        intro.start()
        looper.start()
    }

    override fun onDetachedFromWindow() {
        intro.cancel()
        looper.cancel()
        super.onDetachedFromWindow()
    }

    private fun seg(x: Float, from: Float, to: Float): Float = ((x - from) / (to - from)).coerceIn(0f, 1f)

    private fun totalLength(p: Path): Float {
        val pm = PathMeasure(p, false)
        var sum = 0f
        do { sum += pm.length } while (pm.nextContour())
        return sum
    }

    /** بخشی از مسیر را (به نسبت frac از کل طول) می‌کشد؛ اثر «رسم‌شدن». */
    private fun drawTrimmed(canvas: Canvas, path: Path, total: Float, frac: Float, paint: Paint) {
        if (frac <= 0f) return
        val target = total * frac.coerceAtMost(1f)
        val pm = PathMeasure(path, false)
        var done = 0f
        do {
            val len = pm.length
            val take = (target - done).coerceIn(0f, len)
            if (take > 0f) {
                piece.reset()
                pm.getSegment(0f, take, piece, true)
                canvas.drawPath(piece, paint)
            }
            done += len
        } while (done < target && pm.nextContour())
    }

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height) / 100f
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(s, s)
        canvas.translate(-cx, -cy)

        // ورود کلی: بزرگ‌شدن با کمی جهش
        val enter = seg(t, 0f, 0.3f)
        val grow = OvershootInterpolator(2.2f).getInterpolation(enter)
        val scale = 0.55f + 0.45f * grow

        // هاله‌ی نور پشت حباب
        val glowA = DecelerateInterpolator().getInterpolation(seg(t, 0f, 0.4f))
        glowPaint.alpha = (255 * glowA).toInt()
        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        canvas.drawCircle(cx, cy, 46f, glowPaint)
        canvas.restore()

        // موج‌های پخش‌شونده
        val ringsOn = seg(t, 0.68f, 0.85f)
        if (ringsOn > 0f) {
            for (k in 0 until 3) {
                val phase = (loop + k / 3f) % 1f
                val r = 27f + 21f * phase
                ringPaint.alpha = (150 * (1f - phase) * ringsOn).toInt()
                canvas.drawCircle(cx, cy, r, ringPaint)
            }
        }

        canvas.save()
        canvas.scale(scale, scale, cx, cy)

        // خط دور حباب کشیده می‌شود، بعد داخلش پر می‌شود
        outlinePaint.alpha = 255
        drawTrimmed(canvas, bubble, bubbleLen, seg(t, 0.03f, 0.42f), outlinePaint)
        fillPaint.alpha = (255 * DecelerateInterpolator().getInterpolation(seg(t, 0.34f, 0.56f))).toInt()
        canvas.drawPath(bubble, fillPaint)

        // فلش انتقال
        drawTrimmed(canvas, arrow, arrowLen, DecelerateInterpolator().getInterpolation(seg(t, 0.5f, 0.72f)), arrowPaint)
        canvas.restore()

        // ذره‌های نورانی که دور حباب می‌چرخند
        val dotsOn = seg(t, 0.78f, 1f)
        if (dotsOn > 0f) {
            for (k in 0 until 6) {
                val a = (loop * 2.0 * PI + k * PI / 3.0).toFloat()
                val rr = 41f + 2f * sin(loop * 2.0 * PI + k).toFloat()
                val tw = 0.5f + 0.5f * sin(loop * 4.0 * PI + k * 1.7).toFloat()
                dotPaint.alpha = (230 * dotsOn * tw).toInt()
                canvas.drawCircle(cx + rr * cos(a), cy + rr * sin(a) * 0.92f, 1.1f + 0.7f * tw, dotPaint)
            }
        }
        canvas.restore()
    }
}
