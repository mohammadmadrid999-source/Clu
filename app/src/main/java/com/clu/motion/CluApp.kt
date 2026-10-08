package com.clu.motion

import android.app.Application
import android.content.Context
import com.clu.motion.engine.MotionEngine
import com.clu.motion.profile.ProfileRepository

class CluApp : Application() {

    lateinit var engine: MotionEngine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = MotionEngine(this, ProfileRepository(this))
    }

    companion object {
        fun engine(context: Context): MotionEngine = (context.applicationContext as CluApp).engine
    }
}
