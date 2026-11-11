package com.proxy.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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
        value.contentDescription = if (last == null) context.getString(R.string.rate_unavailable) else null
        value.text = if (last == null) context.getString(R.string.metric_unavailable) else {
            val rate = context.getString(R.string.traffic_rate, formatBytes(if (upload) last.upload else last.download))
            SpannableString(rate).apply {
                setSpan(RelativeSizeSpan(0.57f), rate.indexOf(' ') + 1, rate.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        total.text = context.getString(R.string.traffic_total,
            bytes?.let(::formatBytes) ?: context.getString(R.string.metric_unavailable))
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
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val path = Path()
    private var samples = emptyList<SessionTraffic.Rate>()
    private var upload = false
    private var nowMs = 0L

    internal fun plot(values: List<SessionTraffic.Rate>, isUpload: Boolean, now: Long) {
        samples = values
        upload = isUpload
        nowMs = now
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = paint.strokeWidth
        val bottom = height - inset
        val right = width - inset
        if (right <= inset || bottom <= inset) return
        paint.color = ColorUtils.setAlphaComponent(lineColor, 46)
        canvas.drawLine(inset, bottom, right, bottom, paint)
        if (samples.isEmpty()) return

        fun rate(point: SessionTraffic.Rate) = if (upload) point.upload else point.download
        val peak = samples.maxOf { rate(it) }.coerceAtLeast(1)
        var firstX = 0f
        var lastX = 0f
        var lastY = bottom
        path.rewind()
        samples.forEachIndexed { index, sample ->
            val fraction = 1f - (nowMs - sample.atMs).toFloat() / SessionTraffic.WINDOW_MS
            lastX = inset + fraction * (right - inset)
            lastY = bottom - (rate(sample).toDouble() / peak * (bottom - inset)).toFloat()
            if (index == 0) {
                firstX = lastX
                path.moveTo(lastX, lastY)
            } else path.lineTo(lastX, lastY)
        }
        paint.color = lineColor
        paint.style = Paint.Style.STROKE
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(lastX, lastY, inset, paint)
        path.lineTo(lastX, bottom)
        path.lineTo(firstX, bottom)
        path.close()
        paint.color = ColorUtils.setAlphaComponent(lineColor, 25)
        canvas.drawPath(path, paint)
    }
}
