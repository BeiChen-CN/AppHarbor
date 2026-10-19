package com.app.market.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val RecommendationTabIcon: ImageVector by lazy {
    ImageVector.Builder("Recommendations", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 2.8f); lineTo(14.8f, 8.5f); lineTo(21f, 9.4f)
            lineTo(16.5f, 13.8f); lineTo(17.6f, 20f); lineTo(12f, 17f)
            lineTo(6.4f, 20f); lineTo(7.5f, 13.8f); lineTo(3f, 9.4f)
            lineTo(9.2f, 8.5f); close()
        }
    }.build()
}
