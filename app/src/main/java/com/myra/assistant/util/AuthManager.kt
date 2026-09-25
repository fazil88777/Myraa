package com.myra.assistant.util

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions

/**
 * Gmail (Google) login / signup helper.
 * Needs google-services.json in app/ (from Firebase console) and the
 * app's SHA-1 registered there, otherwise sign-in fails with 12500.
 */
object AuthManager {

    fun client(context: Context): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    fun signedInAccount(context: Context): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(context)

    fun signOut(context: Context, done: () -> Unit) {
        client(context).signOut().addOnCompleteListener { done() }
    }
}
