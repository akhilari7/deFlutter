package com.payatu.flutterinspect.data.models

import android.graphics.drawable.Drawable

data class InstalledApp(
    val packageName: String,
    val appName: String,
    val isFlutter: Boolean = false,
    val icon: Drawable? = null
)
