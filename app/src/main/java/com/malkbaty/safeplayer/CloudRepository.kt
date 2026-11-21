package com.malkbaty.safeplayer

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await

data class CloudVideo(val id: String, val title: String, val favorite: Boolean)

/** No connections or media data leave the device until the user explicitly opens or saves. */
class CloudRepository(private val context: Context) {
    companion object {
        // Configure the shared Firestore instance once per process, before first use.
        private var firestore: FirebaseFirestore? = null
        @Synchronized private fun database(app: FirebaseApp): FirebaseFirestore = firestore ?: FirebaseFirestore.getInstance(app).also {
            it.firestoreSettings = FirebaseFirestoreSettings.Builder().setPersistenceEnabled(false).build()
            firestore = it
        }
    }
    private suspend fun collection(): com.google.firebase.firestore.CollectionReference {
        val app = FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
            ?: throw IllegalStateException("firebase-not-configured")
        val auth = FirebaseAuth.getInstance(app)
        val user = auth.currentUser ?: auth.signInAnonymously().await().user
            ?: throw IllegalStateException("anonymous-auth-failed")
        val db = database(app)
        return db.collection("users").document(user.uid).collection("videos")
    }

    suspend fun save(entry: MediaEntry) {
        require(entry.type == "video")
        // Explicit metadata only. Never upload URI, file bytes or playback position.
        collection().add(mapOf("title" to entry.title, "favorite" to entry.favorite)).await()
    }

    suspend fun list(): List<CloudVideo> = collection().get(Source.SERVER).await().documents.map {
        CloudVideo(it.id, it.getString("title") ?: "بدون عنوان", it.getBoolean("favorite") ?: false)
    }

    suspend fun remove(id: String) { collection().document(id).delete().await() }
}
