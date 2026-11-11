package com.proxy.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.card.MaterialCardView
import java.util.Locale

class TrafficMetricView(context: Context, attrs: AttributeSet?) : MaterialCardView(context, attrs) {
    private val label: TextView
    private val value: TextView
    private val total: TextView
    private val chart: TrafficChartView
    private var upload = false

    init {
        clipToOutline = true
        inflate(context, R.layout.view_traffic_metric, this)
        label = findViewById(R.id.metricLabel)
        value = findViewById(R.id.metricRate)
        total = findViewById(R.id.metricTotal)
        chart = findViewById(R.id.metricChart)
    }

    internal fun configure(isUpload: Boolean) {
        upload = isUpload
        val color = ContextCompat.getColor(context, if (upload) R.color.traffic_upload else R.color.app_primary)
        setCardBackgroundColor(ContextCompat.getColor(context,
            if (upload) R.color.traffic_upload_surface else R.color.traffic_download_surface))
        label.setText(if (upload) R.string.traffic_upload else R.string.traffic_download)
        label.setTextColor(color)
        val arrow = ContextCompat.getDrawable(context, if (upload) R.drawable.ic_upload else R.drawable.ic_download)
        arrow?.mutate()?.setTint(color)
        label.setCompoundDrawablesRelativeWithIntrinsicBounds(arrow, null, null, null)
        chart.lineColor = color
    }

    internal fun render(samples: List<SessionTraffic.Rate>, bytes: Long?, nowMs: Long) {
        val last = samples.lastOrNull()
        val rate = if (last == null) context.getString(R.string.metric_unavailable)
            else context.getString(R.string.traffic_rate, formatBytes(if (upload) last.upload else last.download))
        if (value.text.toString() != rate) {
            value.contentDescription = if (last == null) context.getString(R.string.rate_unavailable) else null
            value.text = if (last == null) rate else SpannableString(rate).apply {
                setSpan(RelativeSizeSpan(0.57f), rate.indexOf(' ') + 1, rate.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val cumulative = context.getString(R.string.traffic_total,
            bytes?.let(::formatBytes) ?: context.getString(R.string.metric_unavailable))
        if (total.text != cumulative) total.text = cumulative
        chart.plot(samples, upload, nowMs)
    }

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
        var size = bytes.toDouble()
        var unit = 0
        while (size >= 1024 && unit < units.lastIndex) {
            size /= 1024
            unit++
        }
        return if (unit == 0) "$bytes B" else String.format(Locale.getDefault(), "%.1f %s", size, units[unit])
    }
}

class TrafficChartView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    internal var lineColor = 0
        set(value) {
            field = value
            updateFill()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val density = resources.displayMetrics.density
    private val path = Path()
    private val area = Path()
    private var fill: LinearGradient? = null
    private var samples = emptyList<SessionTraffic.Rate>()
    private var upload = false
    private var nowMs = 0L

    internal fun plot(values: List<SessionTraffic.Rate>, isUpload: Boolean, now: Long) {
        samples = values
        upload = isUpload
        nowMs = now
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateFill()
    }

    private fun updateFill() {
        fill = if (height > 0) LinearGradient(0f, 0f, 0f, height.toFloat(),
            ColorUtils.setAlphaComponent(lineColor, 70), ColorUtils.setAlphaComponent(lineColor, 9), Shader.TileMode.CLAMP)
        else null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = 3f * density
        val bottom = height - 6f * density
        val right = width - inset
        if (right <= inset || bottom <= inset) return
        paint.strokeWidth = density
        paint.color = ColorUtils.setAlphaComponent(lineColor, 27)
        canvas.drawLine(0f, bottom, width.toFloat(), bottom, paint)
        if (samples.isEmpty()) return

        fun rate(point: SessionTraffic.Rate) = if (upload) point.upload else point.download
        val peak = samples.maxOf { rate(it) }.coerceAtLeast(1)
        var firstX = 0f
        var lastX = 0f
        var lastY = bottom
        path.rewind()
        samples.forEachIndexed { index, sample ->
            val fraction = 1f - (nowMs - sample.atMs).toFloat() / SessionTraffic.WINDOW_MS
            val x = inset + fraction * (right - inset)
            val y = bottom - (rate(sample).toDouble() / peak * (bottom - inset)).toFloat()
            if (index == 0) {
                firstX = x
                path.moveTo(x, y)
            } else {
                // Horizontal tangents keep each curve between its two measured rates.
                val middle = (lastX + x) / 2f
                path.cubicTo(middle, lastY, middle, y, x, y)
            }
            lastX = x
            lastY = y
        }
        area.set(path)
        area.lineTo(lastX, height.toFloat())
        area.lineTo(firstX, height.toFloat())
        area.close()
        paint.color = android.graphics.Color.WHITE
        paint.shader = fill
        canvas.drawPath(area, paint)
        paint.shader = null
        paint.color = lineColor
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.8f * density
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(lastX, lastY, 2.1f * density, paint)
    }
}
