package io.github.joey7500.vaultflow.backup

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import io.github.joey7500.vaultflow.core.security.SecretVault
import io.github.joey7500.vaultflow.data.remote.Http
import io.github.joey7500.vaultflow.data.repository.BackupManager
import io.github.joey7500.vaultflow.data.repository.SettingsStore
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Access to the user's Google Drive with the narrow `drive.file` scope:
 * VaultFlow can only see and change files it created itself (its backup
 * folder), nothing else in the Drive.
 */
object DriveAuth {
    private val SCOPE = Scope("https://www.googleapis.com/auth/drive.file")

    fun request(): AuthorizationRequest = AuthorizationRequest.builder().setRequestedScopes(listOf(SCOPE)).build()

    /** Access token without UI, or null when the user must (re)connect in Settings. */
    suspend fun silentToken(context: Context): String? = runCatching {
        val r: AuthorizationResult = Identity.getAuthorizationClient(context).authorize(request()).await()
        if (r.hasResolution()) null else r.accessToken
    }.getOrNull()

    suspend fun clear(context: Context, token: String) {
        runCatching { Identity.getAuthorizationClient(context).clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
    }
}

data class DriveFile(val id: String, val name: String, val createdTime: String, val size: Long)

class DriveException(message: String, val code: Int = 0) : Exception(message)

/** Minimal Drive v3 REST client (OkHttp + kotlinx.serialization, no Google API client library). */
class DriveClient(private val token: String) {
    private val api = "https://www.googleapis.com/drive/v3/files"
    private val upload = "https://www.googleapis.com/upload/drive/v3/files"

    private suspend fun call(request: Request.Builder): String = withContext(Dispatchers.IO) {
        Http.client.newCall(request.header("Authorization", "Bearer $token").build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw DriveException("Google Drive error ${r.code}", r.code)
            body
        }
    }

    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun ensureFolder(name: String): String {
        val query = "name = '$name' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val found = Http.json.parseToJsonElement(call(Request.Builder().url("$api?q=${q(query)}&fields=files(id)&spaces=drive")))
        (found as? JsonObject)?.get("files")?.let { it as? JsonArray }?.firstOrNull()?.let {
            return (it as JsonObject)["id"]!!.jsonPrimitive.content
        }
        val meta = """{"name":"$name","mimeType":"application/vnd.google-apps.folder"}"""
        val created = Http.json.parseToJsonElement(call(Request.Builder().url("$api?fields=id").post(meta.toRequestBody(JSON))))
        return (created as JsonObject)["id"]!!.jsonPrimitive.content
    }

    suspend fun upload(folderId: String, fileName: String, content: ByteArray): String {
        val meta = """{"name":"$fileName","parents":["$folderId"]}"""
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(meta.toRequestBody(JSON))
            .addPart(content.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        val r = Http.json.parseToJsonElement(call(Request.Builder().url("$upload?uploadType=multipart&fields=id").post(body)))
        return (r as JsonObject)["id"]!!.jsonPrimitive.content
    }

    suspend fun list(folderId: String): List<DriveFile> {
        val query = "'$folderId' in parents and trashed = false"
        val r = Http.json.parseToJsonElement(
            call(Request.Builder().url("$api?q=${q(query)}&orderBy=createdTime%20desc&pageSize=200&fields=files(id,name,createdTime,size)")),
        ) as JsonObject
        return (r["files"] as? JsonArray).orEmpty().map { e ->
            val o = e as JsonObject
            DriveFile(
                o["id"]!!.jsonPrimitive.content,
                o["name"]?.jsonPrimitive?.content.orEmpty(),
                o["createdTime"]?.jsonPrimitive?.content.orEmpty(),
                o["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            )
        }
    }

    suspend fun download(fileId: String): String = call(Request.Builder().url("$api/$fileId?alt=media"))

    suspend fun delete(fileId: String) {
        call(Request.Builder().url("$api/$fileId").delete())
    }

    private companion object {
        val JSON = "application/json; charset=UTF-8".toMediaType()
    }
}

/**
 * Daily encrypted backup of the whole vault to the user's own Google Drive.
 * The file is encrypted on the phone with the backup password (PBKDF2 +
 * AES-256-GCM) before upload, so Google only ever stores ciphertext.
 */
class DriveBackupService(
    private val context: Context,
    private val backup: BackupManager,
    private val vault: SecretVault,
    private val settings: SettingsStore,
) {
    val hasPassword: Boolean get() = vault.has(SecretVault.BACKUP_PASSWORD)

    suspend fun isConnected(): Boolean = settings.get(SettingsStore.DRIVE_CONNECTED) == "true"

    suspend fun markConnected(connected: Boolean) = settings.put(SettingsStore.DRIVE_CONNECTED, connected.toString())

    private suspend fun <T> withDrive(block: suspend (DriveClient) -> T): T {
        val token = DriveAuth.silentToken(context) ?: throw DriveException("Google Drive is not connected. Connect it in Settings → Backup.")
        return try {
            block(DriveClient(token))
        } catch (e: DriveException) {
            if (e.code != 401) throw e
            DriveAuth.clear(context, token) // stale token: get a fresh one once
            val fresh = DriveAuth.silentToken(context) ?: throw DriveException("Google Drive access expired. Reconnect in Settings → Backup.")
            block(DriveClient(fresh))
        }
    }

    /** Encrypts the vault and uploads it; keeps the newest [KEEP] backups. Returns the file name. */
    suspend fun backupNow(): String = lock.withLock {
        val password = vault.get(SecretVault.BACKUP_PASSWORD) ?: throw DriveException("Set a backup password first.")
        try {
            val blob = backup.export(password.toCharArray())
            val name = "vaultflow-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")) + ".vfbackup"
            withDrive { drive ->
                val folder = drive.ensureFolder(FOLDER)
                drive.upload(folder, name, blob.toByteArray(Charsets.UTF_8))
                drive.list(folder).filter { it.name.endsWith(".vfbackup") }.drop(KEEP).forEach { runCatching { drive.delete(it.id) } }
            }
            settings.putLong(SettingsStore.DRIVE_LAST_BACKUP_AT, System.currentTimeMillis())
            settings.put(SettingsStore.DRIVE_LAST_RESULT, "OK · $name")
            name
        } catch (e: Exception) {
            settings.put(SettingsStore.DRIVE_LAST_RESULT, "Error: ${e.message ?: e.javaClass.simpleName}")
            throw e
        }
    }

    suspend fun listBackups(): List<DriveFile> = withDrive { drive ->
        drive.list(drive.ensureFolder(FOLDER)).filter { it.name.endsWith(".vfbackup") }
    }

    /** Downloads and restores a backup (replaces all data). Returns restored transaction count. */
    suspend fun restore(file: DriveFile, password: CharArray): Int {
        val blob = withDrive { it.download(file.id) }
        return backup.import(blob, password)
    }

    companion object {
        const val FOLDER = "VaultFlow backups"
        const val KEEP = 30
        private val lock = Mutex()
    }
}
