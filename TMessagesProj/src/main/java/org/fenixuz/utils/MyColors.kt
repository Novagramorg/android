package org.fenixuz.utils

import android.graphics.Color
import androidx.core.graphics.toColorInt

object MyColors {

//    #34495e first old color
//    var mainColor = (0xff117864).toColorInt()
//    var secondActionBarColor = (0xff15a085).toBigInteger()

    var mainColor = Color.parseColor("#2b87e3")
    var secondActionBarColor = Color.parseColor("#5d6d7e")

    // Secret-chat lock screen — "ocean blue" motion gradient (4 blended shades, matches the Novagram logo).
    // Change these to retune the PIN-entry background colour.
    var secretLockColor1 = Color.parseColor("#0D6EBF")
    var secretLockColor2 = Color.parseColor("#073B6B")
    var secretLockColor3 = Color.parseColor("#1184D6")
    var secretLockColor4 = Color.parseColor("#069DFD")

}