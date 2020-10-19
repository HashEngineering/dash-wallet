/*
 * Copyright 2020 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui.dashpay.widget

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView


class BlurredImageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0) : AppCompatImageView(context, attrs, defStyle) {

    var blurredBitmap: Bitmap? = null

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        val bitmap = (drawable as BitmapDrawable?)?.bitmap

        if (bitmap != null) {
            if (blurredBitmap == null) {
                blurredBitmap = blur(this.context, bitmap)
            }

            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val colorMatrix = ColorMatrix()
            val colorScale = 0.9f
            colorMatrix.setScale(colorScale, colorScale, colorScale, 1f)
            paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
            
            canvas.drawBitmap(blurredBitmap!!, this.matrix, paint)
        }
    }

    private val blurRadius = 25f

    private fun blur(ctx: Context?, inputBitmap: Bitmap): Bitmap? {
        val outputBitmap = Bitmap.createBitmap(inputBitmap)
        val rs: RenderScript = RenderScript.create(ctx)
        val theIntrinsic = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
        val tmpIn: Allocation = Allocation.createFromBitmap(rs, inputBitmap)
        val tmpOut: Allocation = Allocation.createFromBitmap(rs, outputBitmap)
        theIntrinsic.setRadius(blurRadius)
        theIntrinsic.setInput(tmpIn)
        theIntrinsic.forEach(tmpOut)
        tmpOut.copyTo(outputBitmap)
        return outputBitmap
    }
}