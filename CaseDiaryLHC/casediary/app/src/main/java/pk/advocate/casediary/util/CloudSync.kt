package pk.advocate.casediary.util

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser

/**
 * Optional, additive cloud sync (see ../../../../../../../CLOUD-SETUP.md) —
 * everything here is a no-op until a real Firebase project's
 * google-services.json has been added, at which point [isReady] becomes
 * true. The rest of the app never has to know whether that's happened;
 * every call here is safe to make regardless.
 *
 * This first version only wires up sign-in — pushing/pulling actual case
 * data and registering for push notifications is a deliberately separate,
 * later step once this part is confirmed not to have broken anything.
 */
object CloudSync {

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
}
