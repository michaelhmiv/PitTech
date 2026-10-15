package com.pittech.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class) class AccountStorageTest {
    @Test fun pendingOperationsAndRevocationSurviveRestartInEncryptedDeviceOnlyStorage() {
        val context=ApplicationProvider.getApplicationContext<Context>(); val namespace="test-"+UUID.randomUUID().toString()
        val file=File(context.noBackupFilesDir,"account-$namespace-v1/state")
        val alias="pittech-account-$namespace-v1"; val token="A".repeat(43)
        try {
            val vault=AccountVault(context,namespace)
            val value=AccountState(challenge=AccountChallenge(UUID.randomUUID().toString(),"cook@example.com",token,0,true,UUID.randomUUID().toString(),"123456"),logoutToken=token)
            vault.save(value); assertEquals(value,AccountVault(context,namespace).load())
            val encrypted=file.readBytes(); assertFalse(String(encrypted,Charsets.ISO_8859_1).contains(token)); assertFalse(String(encrypted,Charsets.ISO_8859_1).contains("cook@example.com"))
            val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }; assertNull(store.getKey(alias,null).encoded)
            vault.save(AccountState(logoutToken=token)); assertNull(vault.load().challenge); assertNull(vault.load().session); assertEquals(token,vault.load().logoutToken)
            file.writeBytes(file.readBytes().also { it[it.lastIndex]=(it.last().toInt() xor 1).toByte() })
            assertThrows(Exception::class.java) { vault.load() }
        } finally { file.parentFile!!.deleteRecursively(); KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
    }
}
