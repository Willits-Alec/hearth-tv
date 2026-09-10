package com.alec.hearthtv.remote

import com.alec.hearthtv.protocol.bravia.BraviaCredentials

/** Where the TV registration lives between launches. The phone uses DataStore; tests use memory. */
interface CredentialStore {
    suspend fun load(): BraviaCredentials
    suspend fun save(credentials: BraviaCredentials)
}

class InMemoryCredentialStore(var saved: BraviaCredentials = BraviaCredentials.None) : CredentialStore {
    override suspend fun load(): BraviaCredentials = saved
    override suspend fun save(credentials: BraviaCredentials) { saved = credentials }
}
