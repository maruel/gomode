// Keeps one native local-model owner across activity replacement and drained voice teardown.
package com.fghbuild.gomode

import android.app.Application
import com.fghbuild.gomode.voice.LocalModelOwner

class GoModeApplication : Application() {
    internal val localModelOwner by lazy { LocalModelOwner(this) }

    override fun onTerminate() {
        localModelOwner.close()
        super.onTerminate()
    }
}
