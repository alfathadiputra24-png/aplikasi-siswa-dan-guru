package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.lifecycleScope
import com.example.ui.theme.MyApplicationTheme
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthWebException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.abs

class MainActivity : ComponentActivity() {

  companion object {
    private const val TAG = "KelasKuMain"
    private const val LOCAL_HOST = "appassets.androidplatform.net"
    private const val ENTRY_URL = "https://appassets.androidplatform.net/assets/index.html?embedded=android"
  }

  private lateinit var credentialManager: CredentialManager
  private var webViewRef: WebView? = null
  private var pendingTabFromIntent: String? = null

  private val notificationPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      runOnUiThread {
        webViewRef?.evaluateJavascript(
          "window.__onNotificationPermissionResult && window.__onNotificationPermissionResult($granted);",
          null
        )
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    credentialManager = CredentialManager.create(this)
    TaskReminderReceiver.ensureChannel(this)
    pendingTabFromIntent = intent?.getStringExtra("open_tab")

    setContent {
      MyApplicationTheme {
        KelasKuWebShell(
          onWebViewReady = { webView ->
            webViewRef = webView
            checkExistingSessionAndAutoSignIn()
            pendingTabFromIntent?.let { tab ->
              val tabJson = JSONObject.quote(tab)
              webView.evaluateJavascript("window.__kelaskuOpenTab && window.__kelaskuOpenTab($tabJson);", null)
              pendingTabFromIntent = null
            }
          },
          onBackRequested = { webView ->
            webView.evaluateJavascript("window.__kelaskuHandleBack && window.__kelaskuHandleBack()") { result ->
              if (result != "true") {
                if (webView.canGoBack()) {
                  webView.goBack()
                } else {
                  finish()
                }
              }
            }
          }
        )
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    val tab = intent.getStringExtra("open_tab") ?: return
    val tabJson = JSONObject.quote(tab)
    webViewRef?.evaluateJavascript("window.__kelaskuOpenTab && window.__kelaskuOpenTab($tabJson);", null)
  }

  private fun checkExistingSessionAndAutoSignIn() {
    val existing = Firebase.auth.currentUser
    if (existing != null) {
      dispatchNativeAuthSuccess(existing, existing.providerData.lastOrNull()?.providerId ?: "google")
      return
    }
    val clientId = try {
      getString(R.string.default_web_client_id)
    } catch (_: Exception) {
      return
    }
    val googleIdOption = GetGoogleIdOption.Builder()
      .setFilterByAuthorizedAccounts(true)
      .setServerClientId(clientId)
      .setAutoSelectEnabled(true)
      .build()
    val request = GetCredentialRequest.Builder()
      .addCredentialOption(googleIdOption)
      .build()
    lifecycleScope.launch {
      try {
        val cred = credentialManager.getCredential(this@MainActivity, request).credential
        if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
          val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
          val authResult = Firebase.auth.signInWithCredential(
            GoogleAuthProvider.getCredential(token, null)
          ).await()
          authResult.user?.let { dispatchNativeAuthSuccess(it, "google") }
        }
      } catch (_: Exception) {
        // Silent auto sign-in unavailable; user signs in interactively.
      }
    }
  }

  @SuppressLint("SetJavaScriptEnabled")
  @Composable
  private fun KelasKuWebShell(
    onWebViewReady: (WebView) -> Unit,
    onBackRequested: (WebView) -> Unit
  ) {
    val density = LocalDensity.current
    val statusTopDp = with(density) { WindowInsets.statusBars.getTop(this).toDp().value }
    val navBottomDp = with(density) { WindowInsets.navigationBars.getBottom(this).toDp().value }
    var attachedWebView by remember { mutableStateOf<WebView?>(null) }

    BackHandler(enabled = attachedWebView != null) {
      attachedWebView?.let(onBackRequested)
    }

    Box(modifier = Modifier.fillMaxSize()) {
      AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
          WebView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.TRANSPARENT)
            overScrollMode = WebView.OVER_SCROLL_NEVER

            settings.apply {
              javaScriptEnabled = true
              domStorageEnabled = true
              databaseEnabled = true
              useWideViewPort = true
              loadWithOverviewMode = true
              cacheMode = WebSettings.LOAD_DEFAULT
              mediaPlaybackRequiresUserGesture = false
            }

            addJavascriptInterface(KelasKuAuthBridge(this@MainActivity), "KelasKuAndroid")

            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
              override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
              ): WebResourceResponse? {
                val uri = request?.url ?: return null
                if (uri.host == LOCAL_HOST && uri.path == "/assets/index.html") {
                  return try {
                    val stream = ctx.assets.open("index.html")
                    WebResourceResponse(
                      "text/html",
                      "utf-8",
                      200,
                      "OK",
                      mapOf("Cache-Control" to "no-cache"),
                      stream
                    )
                  } catch (e: Exception) {
                    Log.e(TAG, "Failed to open assets/index.html", e)
                    null
                  }
                }
                return super.shouldInterceptRequest(view, request)
              }

              override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
              ): Boolean {
                val uri = request?.url ?: return false
                if (uri.host == LOCAL_HOST) return false
                if (uri.scheme == "https" || uri.scheme == "http") {
                  try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                  } catch (_: Exception) {
                  }
                  return true
                }
                return false
              }

              override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val safeScript = """
                  (function() {
                    document.documentElement.style.setProperty('--safe-top', '${statusTopDp}px');
                    document.documentElement.style.setProperty('--safe-bottom', '${navBottomDp}px');
                    document.body && document.body.classList.add('android-host');
                  })();
                """.trimIndent()
                view?.evaluateJavascript(safeScript, null)
                view?.let(onWebViewReady)
              }
            }

            loadUrl(ENTRY_URL)
            attachedWebView = this
          }
        },
        update = { webView ->
          val insetScript = """
            document.documentElement.style.setProperty('--safe-top', '${statusTopDp}px');
            document.documentElement.style.setProperty('--safe-bottom', '${navBottomDp}px');
          """.trimIndent()
          webView.evaluateJavascript(insetScript, null)
        }
      )
    }
  }

  private fun dispatchNativeAuthSuccess(user: FirebaseUser, provider: String) {
    val json = JSONObject().apply {
      put("uid", user.uid)
      put("displayName", user.displayName ?: user.email?.substringBefore("@") ?: "Pengguna KelasKu")
      put("email", user.email ?: "")
      put("photoURL", user.photoUrl?.toString() ?: "")
      put("provider", provider)
    }
    runOnUiThread {
      webViewRef?.evaluateJavascript("window.__onNativeAuthSuccess && window.__onNativeAuthSuccess($json);", null)
    }
  }

  private fun dispatchNativeAuthError(provider: String, message: String) {
    val pJson = JSONObject.quote(provider)
    val mJson = JSONObject.quote(message)
    runOnUiThread {
      webViewRef?.evaluateJavascript("window.__onNativeAuthError && window.__onNativeAuthError($pJson, $mJson);", null)
    }
  }

  private fun dispatchNativeAuthCancelled(provider: String) {
    val pJson = JSONObject.quote(provider)
    runOnUiThread {
      webViewRef?.evaluateJavascript("window.__onNativeAuthCancelled && window.__onNativeAuthCancelled($pJson);", null)
    }
  }

  inner class KelasKuAuthBridge(private val activity: Activity) {

    @JavascriptInterface
    fun isAndroidHost(): Boolean = true

    @JavascriptInterface
    fun getDefaultWebClientId(): String {
      return try {
        activity.getString(R.string.default_web_client_id)
      } catch (_: Exception) {
        ""
      }
    }

    @JavascriptInterface
    fun signInWithGoogle() {
      val clientId = try {
        activity.getString(R.string.default_web_client_id)
      } catch (_: Exception) {
        dispatchNativeAuthError("google", "Konfigurasi Google Sign-In (default_web_client_id) tidak ditemukan.")
        return
      }
      val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
      val request = GetCredentialRequest.Builder().addCredentialOption(signInOption).build()

      lifecycleScope.launch {
        try {
          val cred = credentialManager.getCredential(activity, request).credential
          if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
            val result = Firebase.auth.signInWithCredential(
              GoogleAuthProvider.getCredential(token, null)
            ).await()
            val user = result.user
            if (user != null) {
              dispatchNativeAuthSuccess(user, "google")
            } else {
              dispatchNativeAuthError("google", "Gagal memuat profil akun Google.")
            }
          } else {
            dispatchNativeAuthError("google", "Jenis kredensial Google tidak dikenali.")
          }
        } catch (e: GetCredentialCancellationException) {
          Log.w(TAG, "Google Sign-In cancelled or dismissed: ${e.message}", e)
          dispatchNativeAuthCancelled("google")
        } catch (e: NoCredentialException) {
          Log.w(TAG, "No Google credential on device; falling back to OAuth browser flow", e)
          launchOAuthProvider("google", "google.com", listOf("email", "profile"))
        } catch (e: GetCredentialProviderConfigurationException) {
          Log.w(TAG, "Credential provider unavailable; falling back to OAuth browser flow", e)
          launchOAuthProvider("google", "google.com", listOf("email", "profile"))
        } catch (e: GetCredentialUnsupportedException) {
          Log.w(TAG, "Credential Manager unsupported; falling back to OAuth browser flow", e)
          launchOAuthProvider("google", "google.com", listOf("email", "profile"))
        } catch (e: GetCredentialException) {
          Log.e(TAG, "Credential Manager error: ${e.type} - ${e.message}", e)
          launchOAuthProvider("google", "google.com", listOf("email", "profile"))
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.e(TAG, "Google Sign-In failed", e)
          dispatchNativeAuthError("google", mapAuthError("google", e))
        }
      }
    }

    @JavascriptInterface
    fun signInWithApple() {
      launchOAuthProvider("apple", "apple.com", listOf("email", "name"))
    }

    private fun launchOAuthProvider(providerKey: String, providerId: String, scopes: List<String>) {
      runOnUiThread {
        if (activity.isFinishing || activity.isDestroyed) {
          dispatchNativeAuthError(providerKey, "Aktivitas aplikasi sedang ditutup. Silakan coba lagi.")
          return@runOnUiThread
        }
        try {
          val provider = OAuthProvider.newBuilder(providerId)
            .setScopes(scopes)
            .build()

          val pending = Firebase.auth.pendingAuthResult
          if (pending != null) {
            pending
              .addOnSuccessListener { result ->
                val user = result.user
                if (user != null) {
                  val actualProvider = if (result.additionalUserInfo?.providerId?.contains("apple") == true) "apple" else providerKey
                  dispatchNativeAuthSuccess(user, actualProvider)
                } else {
                  dispatchNativeAuthError(providerKey, "Gagal memuat profil akun.")
                }
              }
              .addOnFailureListener { e ->
                Log.w(TAG, "Pending OAuth result failed for $providerId: ${e.message}", e)
                val code = (e as? FirebaseAuthException)?.errorCode
                if (code == "ERROR_WEB_CONTEXT_CANCELED" || code == "ERROR_WEB_CONTEXT_ALREADY_PRESENTED") {
                  startFreshOAuth(providerKey, provider)
                } else {
                  dispatchNativeAuthError(providerKey, mapAuthError(providerKey, e))
                }
              }
            return@runOnUiThread
          }

          startFreshOAuth(providerKey, provider)
        } catch (e: Exception) {
          Log.e(TAG, "Failed to start OAuth flow for $providerId", e)
          dispatchNativeAuthError(providerKey, mapAuthError(providerKey, e))
        }
      }
    }

    private fun startFreshOAuth(providerKey: String, provider: OAuthProvider) {
      try {
        Firebase.auth
          .startActivityForSignInWithProvider(activity, provider)
          .addOnSuccessListener { result ->
            val user = result.user
            if (user != null) {
              dispatchNativeAuthSuccess(user, providerKey)
            } else {
              dispatchNativeAuthError(providerKey, "Gagal memuat profil akun.")
            }
          }
          .addOnFailureListener { e ->
            Log.e(TAG, "OAuth sign-in failed for $providerKey", e)
            val code = (e as? FirebaseAuthException)?.errorCode
            if (code == "ERROR_WEB_CONTEXT_CANCELED") {
              dispatchNativeAuthCancelled(providerKey)
            } else {
              dispatchNativeAuthError(providerKey, mapAuthError(providerKey, e))
            }
          }
      } catch (e: Exception) {
        Log.e(TAG, "Cannot open browser/Custom Tab for OAuth $providerKey", e)
        dispatchNativeAuthError(providerKey, mapAuthError(providerKey, e))
      }
    }

    @JavascriptInterface
    fun signOut() {
      lifecycleScope.launch {
        withContext(NonCancellable) {
          try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
          } catch (e: Exception) {
            Log.w(TAG, "Failed to clear credential state: ${e.message}")
          } finally {
            Firebase.auth.signOut()
            withContext(Dispatchers.Main) {
              webViewRef?.evaluateJavascript(
                "window.__onNativeSignOutComplete && window.__onNativeSignOutComplete();",
                null
              )
            }
          }
        }
      }
    }

    @JavascriptInterface
    fun openExternalUrl(url: String) {
      if (url.startsWith("https://") || url.startsWith("http://")) {
        runOnUiThread {
          try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
          } catch (_: Exception) {
          }
        }
      }
    }

    @JavascriptInterface
    fun getNotificationPermissionStatus(): String {
      return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val granted = ContextCompat.checkSelfPermission(
          activity,
          Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) "granted" else "denied"
      } else {
        if (NotificationManagerCompat.from(activity).areNotificationsEnabled()) "granted" else "denied"
      }
    }

    @JavascriptInterface
    fun requestNotificationPermission() {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val granted = ContextCompat.checkSelfPermission(
          activity,
          Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
          runOnUiThread {
            webViewRef?.evaluateJavascript(
              "window.__onNotificationPermissionResult && window.__onNotificationPermissionResult(true);",
              null
            )
          }
        } else {
          notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
      } else {
        val enabled = NotificationManagerCompat.from(activity).areNotificationsEnabled()
        runOnUiThread {
          webViewRef?.evaluateJavascript(
            "window.__onNotificationPermissionResult && window.__onNotificationPermissionResult($enabled);",
            null
          )
        }
      }
    }

    @JavascriptInterface
    fun sendTestReminderNow(title: String, subject: String, dueDate: String) {
      val notifId = 9000 + abs(title.hashCode() % 900)
      TaskReminderReceiver.showNotification(activity, notifId, title, subject, dueDate)
    }

    @JavascriptInterface
    fun syncTaskReminders(tasksJson: String, enabled: Boolean) {
      scheduleH1TaskReminders(tasksJson, enabled)
    }
  }

  private fun scheduleH1TaskReminders(tasksJson: String, enabled: Boolean) {
    val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val prefs = getSharedPreferences("kelasku_reminders", Context.MODE_PRIVATE)
    val prevIds = prefs.getStringSet("scheduled_codes", emptySet()) ?: emptySet()

    // Batalkan alarm lama agar tugas selesai/dihapus tidak memicu notifikasi usang
    for (codeStr in prevIds) {
      val code = codeStr.toIntOrNull() ?: continue
      val cancelIntent = Intent(this, TaskReminderReceiver::class.java)
      val pi = PendingIntent.getBroadcast(
        this,
        code,
        cancelIntent,
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
      )
      if (pi != null) {
        alarmManager.cancel(pi)
        pi.cancel()
      }
    }

    if (!enabled) {
      prefs.edit().putStringSet("scheduled_codes", emptySet()).apply()
      return
    }

    val newCodes = mutableSetOf<String>()
    val nowMs = System.currentTimeMillis()
    val todayCal = Calendar.getInstance()
    val todayKey = "${todayCal.get(Calendar.YEAR)}-${todayCal.get(Calendar.MONTH) + 1}-${todayCal.get(Calendar.DAY_OF_MONTH)}"

    try {
      val arr = JSONArray(tasksJson)
      for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        if (obj.optString("status") == "done") continue
        val id = obj.optString("id")
        val title = obj.optString("title")
        val subject = obj.optString("subject")
        val dueDate = obj.optString("dueDate")
        val parts = dueDate.split("-")
        if (parts.size != 3) continue
        val year = parts[0].toIntOrNull() ?: continue
        val month = (parts[1].toIntOrNull() ?: continue) - 1
        val day = parts[2].toIntOrNull() ?: continue

        // Jadwalkan pengingat H-1 pukul 09:00 waktu lokal
        val triggerCal = Calendar.getInstance().apply {
          set(year, month, day, 9, 0, 0)
          set(Calendar.MILLISECOND, 0)
          add(Calendar.DAY_OF_MONTH, -1)
        }

        var triggerAtMs = triggerCal.timeInMillis
        val isDueTomorrow =
          triggerCal.get(Calendar.YEAR) == todayCal.get(Calendar.YEAR) &&
          triggerCal.get(Calendar.DAY_OF_YEAR) == todayCal.get(Calendar.DAY_OF_YEAR)

        if (triggerAtMs <= nowMs) {
          val alreadySentKey = "sent_${id}_$todayKey"
          if (isDueTomorrow && !prefs.getBoolean(alreadySentKey, false)) {
            triggerAtMs = nowMs + 8_000L
            prefs.edit().putBoolean(alreadySentKey, true).apply()
          } else {
            continue
          }
        }

        val reqCode = 20000 + abs(id.hashCode() % 40000)
        val intent = Intent(this, TaskReminderReceiver::class.java).apply {
          putExtra(TaskReminderReceiver.EXTRA_TASK_ID, id)
          putExtra(TaskReminderReceiver.EXTRA_TITLE, title)
          putExtra(TaskReminderReceiver.EXTRA_SUBJECT, subject)
          putExtra(TaskReminderReceiver.EXTRA_DUE_DATE, dueDate)
          putExtra(TaskReminderReceiver.EXTRA_NOTIFICATION_ID, reqCode)
        }
        val pi = PendingIntent.getBroadcast(
          this,
          reqCode,
          intent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        newCodes.add(reqCode.toString())
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to schedule H-1 reminders", e)
    }

    prefs.edit().putStringSet("scheduled_codes", newCodes).apply()
  }

  private fun mapAuthError(providerKey: String, e: Exception): String {
    val authErr = e as? FirebaseAuthException
    val webErr = e as? FirebaseAuthWebException
    val code = authErr?.errorCode ?: webErr?.errorCode ?: ""
    val rawMsg = (e.message ?: e.localizedMessage ?: "").lowercase()

    if (code == "ERROR_OPERATION_NOT_ALLOWED" || rawMsg.contains("operation is not allowed") || rawMsg.contains("disabled for this firebase project")) {
      return if (providerKey == "apple") {
        "Provider Apple belum diaktifkan di Firebase Console proyek ini (Authentication → Sign-in method → Apple dengan Services ID, Team ID, Key ID, & kunci .p8)."
      } else {
        "Provider Google belum diaktifkan di Firebase Console proyek ini (Authentication → Sign-in method → Google)."
      }
    }
    if (code == "ERROR_NETWORK_REQUEST_FAILED" || rawMsg.contains("network")) {
      return "Koneksi internet bermasalah. Periksa jaringan Anda lalu coba lagi."
    }
    if (code == "ERROR_INVALID_CREDENTIAL" || rawMsg.contains("invalid_credential") || rawMsg.contains("bad token")) {
      return "Token login tidak valid atau kadaluarsa. Pastikan SHA-1 APK & Web Client ID sesuai di Firebase Console."
    }
    if (rawMsg.contains("no browser") || rawMsg.contains("activitynotfound")) {
      return "Browser / Chrome Custom Tabs tidak ditemukan di perangkat untuk membuka halaman login."
    }
    if (providerKey == "apple") {
      return e.localizedMessage ?: "Login Apple gagal. Pastikan provider Apple sudah diaktifkan di Firebase Console."
    }
    return e.localizedMessage ?: "Login Google gagal. Pastikan akun Google & koneksi internet tersedia."
  }
}
