package pk.advocate.casediary.util

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import pk.advocate.casediary.db.Db
import pk.advocate.casediary.db.Fixture
import pk.advocate.casediary.db.TermHit

/**
 * Optional, additive cloud sync (see ../../../../../../../CLOUD-SETUP.md) —
 * everything here is a no-op until a real Firebase project's
 * google-services.json has been added, at which point [isReady] becomes
 * true. The rest of the app never has to know whether that's happened;
 * every call here is safe to make regardless.
 *
 * Mirrors the web app's cloud-sync design: one Firestore document per
 * signed-in user at users/{uid}, same field names the GitHub Actions
 * scraper and the web app both read/write (cases/keys/pending/hits/
 * settings) — see scripts/cloud-scrape/scrape.mjs and casediary.html's
 * cloudBuildPayload(). [push] always merges in any cloud-only hits first,
 * so uploading local edits can never silently wipe out a match the
 * scheduled check already found — same guarantee the web app makes.
 */
object CloudSync {

    private fun firestore() = FirebaseFirestore.getInstance()
    private fun userDoc(uid: String) = firestore().collection("users").document(uid)

    /** True once google-services.json exists and Firebase could initialize from it. */
    fun isReady(context: Context): Boolean =
        try {
            FirebaseApp.getApps(context).isNotEmpty()
        } catch (e: Exception) {
            false
        }

    fun currentUser(): FirebaseUser? =
        try {
            FirebaseAuth.getInstance().currentUser
        } catch (e: Exception) {
            null
        }

    fun signUp(email: String, password: String, onResult: (Boolean, String?) -> Unit) {
        try {
            FirebaseAuth.getInstance().createUserWithEmailAndPassword(email, password)
                .addOnSuccessListener { onResult(true, null) }
                .addOnFailureListener { e -> onResult(false, e.message) }
        } catch (e: Exception) {
            onResult(false, e.message)
        }
    }

    fun signIn(email: String, password: String, onResult: (Boolean, String?) -> Unit) {
        try {
            FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password)
                .addOnSuccessListener { onResult(true, null) }
                .addOnFailureListener { e -> onResult(false, e.message) }
        } catch (e: Exception) {
            onResult(false, e.message)
        }
    }

    fun signOut() {
        try {
            FirebaseAuth.getInstance().signOut()
        } catch (e: Exception) {
            // Nothing to sign out of — fine.
        }
    }

    /** Registers this device's FCM token into the signed-in user's doc, so the
     *  scheduled cloud check (and any future push from this app) can reach it.
     *  Best-effort — a failure here never surfaces to the user. */
    fun registerForPush(context: Context) {
        val user = currentUser() ?: return
        if (!isReady(context)) return
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                userDoc(user.uid).set(mapOf("fcmTokens" to FieldValue.arrayUnion(token)), SetOptions.merge())
            }
        } catch (e: Exception) {
            // Best-effort — push just won't reach this device.
        }
    }

    /** Always merges in any cloud-only hits first, THEN pushes — mirrors the
     *  web app's cloudPush(), so uploading this device's own edits can never
     *  silently wipe out a match the scheduled cloud check already found. */
    fun push(context: Context, onResult: (Boolean, String?) -> Unit) {
        val user = currentUser()
        if (!isReady(context) || user == null) {
            onResult(false, "Not signed in")
            return
        }
        val local = Db.get(context)
        val ref = userDoc(user.uid)
        ref.get()
            .addOnSuccessListener { doc ->
                val remoteHits = doc.get("hits") as? List<*> ?: emptyList<Any?>()
                mergeRemoteHitsIntoLocal(local, remoteHits)
                val payload = buildPayload(local, context)
                payload["updatedAt"] = FieldValue.serverTimestamp()
                ref.set(payload)
                    .addOnSuccessListener { onResult(true, null) }
                    .addOnFailureListener { e -> onResult(false, e.message) }
            }
            .addOnFailureListener { e -> onResult(false, e.message) }
    }

    /** Pulls down any hit the scheduled cloud check (or another device) found
     *  that this device doesn't have yet, by hash — never deletes anything
     *  local. [onDone] receives how many new hits were merged in. */
    fun pullNewHits(context: Context, onDone: (Int) -> Unit) {
        val user = currentUser()
        if (!isReady(context) || user == null) {
            onDone(0)
            return
        }
        val local = Db.get(context)
        userDoc(user.uid).get()
            .addOnSuccessListener { doc ->
                val remoteHits = doc.get("hits") as? List<*> ?: emptyList<Any?>()
                onDone(mergeRemoteHitsIntoLocal(local, remoteHits))
            }
            .addOnFailureListener { onDone(0) }
    }

    /** @return how many hits were new (and so actually merged in). */
    private fun mergeRemoteHitsIntoLocal(local: Db, remoteHits: List<*>): Int {
        if (remoteHits.isEmpty()) return 0
        val fixtures = remoteHits.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val hash = m["hash"] as? String ?: return@mapNotNull null
            val termsRaw = m["terms"] as? List<*> ?: emptyList<Any?>()
            val terms = termsRaw.mapNotNull { t ->
                val tm = t as? Map<*, *> ?: return@mapNotNull null
                val term = tm["term"] as? String ?: return@mapNotNull null
                val kind = tm["kind"] as? String ?: "OTHER"
                TermHit(term, kind)
            }
            Fixture(
                hash = hash,
                caseId = (m["caseId"] as? Number)?.toLong() ?: 0L,
                pendingId = (m["pendingId"] as? Number)?.toLong() ?: 0L,
                sourceLabel = m["source"] as? String ?: "Cloud check",
                sourceUrl = "",
                listDate = m["listDate"] as? String ?: "",
                raw = m["raw"] as? String ?: "",
                terms = terms,
                relatedRaw = emptyList(),
                foundAt = (m["foundAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                seen = false
            )
        }
        if (fixtures.isEmpty()) return 0
        return local.insertFixtures(fixtures).size
    }

    /** Only the fields the GitHub Actions scraper and the web app actually
     *  read (see scripts/cloud-scrape/scrape.mjs's compile()) — kept
     *  deliberately small rather than mirroring every local column. */
    private fun buildPayload(local: Db, context: Context): HashMap<String, Any?> {
        val prefs = Prefs(context)
        val cases = local.listCases(null, null).map { c ->
            mapOf(
                "id" to c.id,
                "caseType" to c.caseType,
                "caseNo" to c.caseNo,
                "caseYear" to c.caseYear,
                "petitioner" to c.petitioner,
                "respondent" to c.respondent,
                "status" to c.status,
                "watched" to c.watched
            )
        }
        val keys = local.listWatchTerms().map { k ->
            mapOf("id" to k.id, "term" to k.term, "kind" to k.kind, "enabled" to k.enabled)
        }
        val pending = local.listPendingFiles().map { p ->
            mapOf(
                "id" to p.id,
                "title" to p.title,
                "caseType" to p.caseType,
                "caseNo" to p.caseNo,
                "caseYear" to p.caseYear
            )
        }
        return hashMapOf(
            "cases" to cases,
            "keys" to keys,
            "pending" to pending,
            "settings" to mapOf(
                "principalSeatOnly" to prefs.principalSeatOnly,
                "pendingFilesEnabled" to prefs.pendingFilesEnabled,
                "cloudScrapeEnabled" to prefs.cloudScrapeEnabled
            )
        )
    }
}
