package com.followup.tracker

import android.app.Application
import androidx.room.Room

internal class JourneyApp : Application() {
    val repository: Repository by lazy {
        Repository(Room.databaseBuilder(this, FollowDatabase::class.java, "you-follow-ups.db")
            .addMigrations(FollowDatabase.migration1To2, FollowDatabase.migration2To3, FollowDatabase.migration3To4).build(), SettingsStore(this))
    }
    val reminders by lazy { Reminders(this, repository) }
    override fun onCreate() {
        super.onCreate()
        reminders.install()
    }
}
