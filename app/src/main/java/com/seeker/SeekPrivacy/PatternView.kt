package com.seeker.seekprivacy

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class PatternView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dots = mutableListOf<Dot>()
    private val selectedDots = mutableListOf<Dot>()
    private val linePaint = Paint().apply {
        color = Color.CYAN
        strokeWidth = 15f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    

    private val dotPaint = Paint().apply { isAntiAlias = true }
    
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isFinished = false


    var onPatternListener: ((String) -> Unit)? = null

    data class Dot(val id: Int, val x: Float, val y: Float)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        dots.clear()
        val spacingW = w / 4f
        val spacingH = h / 4f
        var count = 1
        for (i in 1..3) {
            for (j in 1..3) {
                dots.add(Dot(count++, j * spacingW, i * spacingH))
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (selectedDots.isNotEmpty()) {
            val path = Path()
            path.moveTo(selectedDots[0].x, selectedDots[0].y)
            for (i in 1 until selectedDots.size) {
                path.lineTo(selectedDots[i].x, selectedDots[i].y)
            }
            if (!isFinished) path.lineTo(lastTouchX, lastTouchY)
            canvas.drawPath(path, linePaint)
        }

        dots.forEach { dot ->
            dotPaint.color = if (selectedDots.contains(dot)) Color.CYAN else Color.GRAY
            canvas.drawCircle(dot.x, dot.y, 30f, dotPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isFinished && event.action == MotionEvent.ACTION_DOWN) reset()

        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                isFinished = false
                lastTouchX = event.x
                lastTouchY = event.y
                checkCollision(event.x, event.y)
            }
            MotionEvent.ACTION_UP -> {
                isFinished = true
                val result = selectedDots.joinToString("") { it.id.toString() }
                if (result.isNotEmpty()) onPatternListener?.invoke(result)
            }
        }
        invalidate()
        return true
    }

    private fun checkCollision(x: Float, y: Float) {
        dots.forEach { dot ->
            val dist = Math.sqrt(Math.pow((x - dot.x).toDouble(), 2.0) + Math.pow((y - dot.y).toDouble(), 2.0))
            if (dist < 60 && !selectedDots.contains(dot)) {
                selectedDots.add(dot)
            }
        }
    }

    fun reset() {
        selectedDots.clear()
        isFinished = false
        invalidate()
    }
}
