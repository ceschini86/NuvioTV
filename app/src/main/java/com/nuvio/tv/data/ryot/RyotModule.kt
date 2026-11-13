package com.nuvio.tv.data.ryot

import android.content.Context
import com.nuvio.tv.core.network.NoDelaySocketFactory
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.TrackingHistoryWriter
import com.nuvio.tv.core.tracking.TrackingLibraryProvider
import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProvider
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.ProfileDataStore
import java.io.File
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

// Fork: wiring of the Ryot tracking provider. The upstream TrackingModule is untouched.
@Module
@InstallIn(SingletonComponent::class)
object RyotModule {
    @Provides
    @Singleton
    fun client(): RyotGraphqlClient = RyotGraphqlClient(
        OkHttpRyot(
            OkHttpClient.Builder()
                .socketFactory(NoDelaySocketFactory)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                .build()
        )
    )

    @Provides
    @Singleton
    fun authStore(@ApplicationContext context: Context, profileDataStore: ProfileDataStore): RyotAuthStore {
        val store = RyotAuthStore(AndroidRyotAuthPersistence(context))
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            profileDataStore.activeProfileId.collect(store::selectProfile)
        }
        return store
    }

    @Provides
    @IntoSet
    fun credentialStore(store: RyotAuthStore): ProfileScopedCredentialStore = store

    @Provides
    @Singleton
    fun auth(client: RyotGraphqlClient, store: RyotAuthStore) = RyotAuthRepository(client, store)

    @Provides
    @Singleton
    fun api(client: RyotGraphqlClient) = RyotApi(client)

    @Provides
    @Singleton
    fun ids(tmdb: TmdbService): RyotIdResolver = TmdbRyotIdResolver(tmdb)

    @Provides
    @Singleton
    fun sync(
        @ApplicationContext context: Context,
        store: RyotAuthStore,
        auth: RyotAuthRepository,
        api: RyotApi,
        ids: RyotIdResolver,
        profiles: ProfileManager
    ) = RyotSyncRepository(
        storage = FileRyotSyncStorage(File(context.filesDir, "ryot")),
        store = store,
        auth = auth,
        activeProfileId = profiles.activeProfileId,
        coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        remoteFor = { stored -> RyotApiSyncRemote(api, stored, ids::imdbFor) }
    )

    @Provides
    @Singleton
    fun writer(
        client: RyotGraphqlClient,
        api: RyotApi,
        auth: RyotAuthRepository,
        store: RyotAuthStore,
        sync: RyotSyncRepository,
        ids: RyotIdResolver
    ) = RyotWriter(client, api, auth, store, sync, ids)

    @Provides
    @Singleton
    fun progress(
        sync: RyotSyncRepository,
        store: RyotAuthStore,
        auth: RyotAuthRepository,
        api: RyotApi,
        profiles: ProfileManager,
        layout: LayoutPreferenceDataStore
    ) = RyotTrackingProgressProvider(sync, store, auth, api, profiles, layout)

    @Provides
    @Singleton
    fun library(
        sync: RyotSyncRepository,
        store: RyotAuthStore,
        auth: RyotAuthRepository,
        api: RyotApi,
        client: RyotGraphqlClient,
        ids: RyotIdResolver,
        profiles: ProfileManager
    ) = RyotLibraryProvider(sync, store, auth, api, client, ids, profiles)

    @Provides
    @IntoSet
    fun libraryProvider(provider: RyotLibraryProvider): TrackingLibraryProvider = provider

    @Provides
    @IntoSet
    fun trackingProvider(provider: RyotTrackingProvider): TrackingProvider = provider

    @Provides
    @IntoSet
    fun historyWriter(writer: RyotWriter): TrackingHistoryWriter = writer

    @Provides
    @IntoSet
    fun progressProvider(provider: RyotTrackingProgressProvider): TrackingProgressProvider = provider
}
