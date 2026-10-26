// Gives the independent fixture its own foreground app slot before reclaiming Go Mode's cached process.
package com.fghbuild.gomode.cardialfixture

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class FixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                id = android.R.id.text1
                text = "Car dial test"
            },
        )
    }
}
