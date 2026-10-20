package com.forecash.app

import android.app.Application
import androidx.room.Room
import com.forecash.app.data.ForeCashDatabase

class ForeCashApp : Application() {

    val database: ForeCashDatabase by lazy {
        Room.databaseBuilder(
            applicationContext,
            ForeCashDatabase::class.java,
            "forecash.db"
        )
            .fallbackToDestructiveMigration() // MVP: allow schema changes easily
            .build()
    }
}
