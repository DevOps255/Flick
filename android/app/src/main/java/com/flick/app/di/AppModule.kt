package com.flick.app.di

import android.content.Context
import androidx.room.Room
import com.flick.app.core.PeerDiscovery
import com.flick.app.core.TransferEngine
import com.flick.app.data.db.FlickDatabase
import com.flick.app.data.acoustic.AcousticDiscovery
import com.flick.app.data.acoustic.RealTransferEngine
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class EngineModule {
    @Binds
    abstract fun discovery(impl: AcousticDiscovery): PeerDiscovery

    @Binds
    abstract fun engine(impl: RealTransferEngine): TransferEngine
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): FlickDatabase =
        Room.databaseBuilder(context, FlickDatabase::class.java, "flick.db").build()

    @Provides
    fun transferDao(db: FlickDatabase) = db.transferDao()

    @Provides
    fun friendDao(db: FlickDatabase) = db.friendDao()
}
