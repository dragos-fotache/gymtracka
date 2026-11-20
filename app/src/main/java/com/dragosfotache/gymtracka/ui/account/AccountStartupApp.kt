package com.dragosfotache.gymtracka.ui.account

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.dragosfotache.gymtracka.BuildConfig
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.api.GoogleIdentityException
import com.dragosfotache.gymtracka.data.sync.api.GoogleIdentityFailureReason
import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.http.HttpServerChecker
import com.dragosfotache.gymtracka.data.sync.http.HttpServiceAuthApi
import com.dragosfotache.gymtracka.data.sync.http.OkHttpServiceTransport
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.identity.AndroidGoogleIdentityProvider
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportPolicy
import com.dragosfotache.gymtracka.data.sync.session.AndroidSessionVault
import com.dragosfotache.gymtracka.data.sync.session.AppAccountSession
import com.dragosfotache.gymtracka.data.sync.session.AppSessionException
import com.dragosfotache.gymtracka.data.sync.session.AppSessionDeliveryStatus
import com.dragosfotache.gymtracka.data.sync.session.AppSessionState
import com.dragosfotache.gymtracka.data.sync.session.AppSessionReason
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportProgress
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportProgressPhase
import com.dragosfotache.gymtracka.data.sync.session.ForegroundSignInCoordinator
import com.dragosfotache.gymtracka.data.sync.session.ForegroundSignInIntent
import com.dragosfotache.gymtracka.data.sync.session.RecoveryRoomPhase
import com.dragosfotache.gymtracka.data.sync.session.RecoveryRoomSnapshot
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import com.dragosfotache.gymtracka.ui.AccountGymAppState
import com.dragosfotache.gymtracka.ui.Destination
import com.dragosfotache.gymtracka.ui.GymApp
import com.dragosfotache.gymtracka.ui.components.GymDialog
import com.dragosfotache.gymtracka.ui.components.GymDialogAction
import com.dragosfotache.gymtracka.ui.rememberAccountGymAppState
import com.dragosfotache.gymtracka.ui.settings.ServerConnectionState
import com.dragosfotache.gymtracka.ui.settings.ServerConnectionStatus
import com.dragosfotache.gymtracka.ui.settings.ServerConnectionSection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Fixed redacted startup operation failure. No details escape. */
internal class AccountStartupException(val error: AccountStartupError) :
    RuntimeException("Account startup failure", null, false, true) {
    override fun toString(): String = "AccountStartupException(error=$error)"
}

/**
 * nonsecret startup configuration. Strict original-text parsing only.
 * Invalid origin maps null; invalid audience maps null. No trimming, discovery
 * or raw display. Valid origin with missing audience still recovers offline;
 * foreground sign-in alone is disabled.
 */
internal class AccountStartupConfiguration private constructor(
    val origin: ServiceOrigin?,
    val serverClientId: String?,
    val signInAvailable: Boolean,
) {
    companion object {
        fun fromValues(serviceOrigin: String?, serverClientId: String?): AccountStartupConfiguration {
            val origin = try {
                if (serviceOrigin == null) {
                    null
                } else {
                    ServiceOrigin.configured(serviceOrigin)
                }
            } catch (_: Exception) {
                null
            }
            val audience = if (serverClientId == null ||
                serverClientId.isBlank() ||
                serverClientId.trim() != serverClientId
            ) {
                null
            } else {
                serverClientId
            }
            return AccountStartupConfiguration(origin, audience, origin != null && audience != null)
        }
    }

    override fun toString(): String = "AccountStartupConfiguration(redacted)"
}

/**
 * facade delegation for startup operations. Checks the explicit-save custom
 * editor before any foreground work and throws fixed EDITOR_OPEN with no details.
 * Delegates only through the facade/coordinator; never catches a foreign grant to
 * force another intent. The runtime barrier rechecks after any drain.
 */
internal class SessionAccountStartupOperations(
    private val accountState: AccountGymAppState,
    private val signInCoordinator: ForegroundSignInCoordinator,
) : AccountStartupOperations {
    override val state: StateFlow<AppSessionState>
        get() = accountState.sessionCoordinator.state

    override suspend fun recover(): AppSessionState =
        accountState.sessionCoordinator.recover()

    override suspend fun signIn(
        intent: ForegroundSignInIntent,
        expected: RecoveryRoomSnapshot,
        operationId: String,
    ): AppSessionState {
        val destination = accountState.appState?.destination
        if (destination == Destination.CreateExercise || destination == Destination.EditCustomExercise) {
            throw AccountStartupException(AccountStartupError.EDITOR_OPEN)
        }
        val request = accountState.sessionCoordinator.captureForegroundSignInRequest(
            intent,
            expected,
            operationId,
        )
        return signInCoordinator.signIn(request)
    }

    override suspend fun completeActivation(
        expected: RecoveryRoomSnapshot,
        stageId: String,
    ): AppSessionState =
        accountState.sessionCoordinator.completeActivation(expected, stageId)

    override suspend fun observeLegacyImport() =
        accountState.sessionCoordinator.observeLegacyImport()

    override suspend fun completeActivationAfterReconnect(expected: RecoveryRoomSnapshot, stageId: String): AppSessionState =
        accountState.sessionCoordinator.completeActivation(expected, stageId, connectionRestored = true)

    override fun observeSyncStatus(session: AppAccountSession): Flow<com.dragosfotache.gymtracka.data.sync.session.AccountSyncStatus> =
        accountState.sessionCoordinator.observeSyncStatus(session)
}

/** One application-process vault/run-owner pair for production factories. */
private object ProductionAccountDependencies {
    @Volatile private var vault: com.dragosfotache.gymtracka.data.sync.session.EncryptedSessionVault? = null
    @Volatile private var runOwner: PushRunOwner? = null

    fun vault(context: android.content.Context): com.dragosfotache.gymtracka.data.sync.session.EncryptedSessionVault =
        vault ?: synchronized(this) {
            vault ?: AndroidSessionVault.create(context.applicationContext).also { vault = it }
        }

    fun runOwner(): PushRunOwner =
        runOwner ?: synchronized(this) {
            runOwner ?: PushRunOwner().also { runOwner = it }
        }
}

private class UnconfiguredIdentityProvider :
    com.dragosfotache.gymtracka.data.sync.api.GoogleIdentityProvider {
    override suspend fun credentialFor(nonce: String): OpaqueToken =
        throw GoogleIdentityException(GoogleIdentityFailureReason.UNCONFIGURED)

    override suspend fun clearCredentialState() {
        throw GoogleIdentityException(GoogleIdentityFailureReason.UNCONFIGURED)
    }

    override fun toString(): String = "UnconfiguredIdentityProvider(redacted)"
}

/**
 * Production default factory/root. Uses the LocalContext Activity for identity.
 * Empty configuration renders the unavailable gate with no database/vault/HTTP
 * reads or SDK launch. Configured installs recover offline even when the audience
 * is missing; foreground sign-in alone is then disabled.
 */
@Composable
internal fun AccountStartupApp() {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val activity = context as? ComponentActivity
    val config = remember {
        AccountStartupConfiguration.fromValues(
            BuildConfig.GYMTRACKA_SERVICE_ORIGIN,
            BuildConfig.GYMTRACKA_GOOGLE_SERVER_CLIENT_ID,
        )
    }
    if (config.origin == null) {
        UnconfiguredAccountGate()
        return
    }
    val origin = config.origin
    val database = remember(appContext) { GymDatabase.getInstance(appContext) }
    val vault = remember(appContext) { ProductionAccountDependencies.vault(appContext) }
    val owner = remember(appContext) { ProductionAccountDependencies.runOwner() }
    val transport = remember(origin) { OkHttpServiceTransport(origin) }
    val authApi = remember(origin, transport) { HttpServiceAuthApi(origin, transport) }
    val consent = remember { AccountImportConsent() }
    val policy = remember(consent) { LegacyImportPolicy(consent) }
    val accountState = rememberAccountGymAppState(database, origin, vault, authApi, transport, owner, policy)
    val identityProvider = remember(activity, config.serverClientId) {
        if (activity != null) {
            AndroidGoogleIdentityProvider(activity, config.serverClientId)
        } else {
            UnconfiguredIdentityProvider()
        }
    }
    val signInCoordinator = remember(origin, identityProvider, accountState, authApi) {
        ForegroundSignInCoordinator(origin, identityProvider, accountState.sessionCoordinator, authApi)
    }
    val scope = rememberCoroutineScope()
    val serverChecker = remember(origin) { HttpServerChecker(origin) }
    val serverConnectionState = remember(serverChecker, scope) { ServerConnectionState(serverChecker, scope) }
    DisposableEffect(serverConnectionState) {
        onDispose(serverConnectionState::dispose)
    }
    val operations = remember(accountState, signInCoordinator) {
        SessionAccountStartupOperations(accountState, signInCoordinator)
    }
    val startupState = remember(operations, config.signInAvailable, scope) {
        AccountStartupState(operations, config.signInAvailable, scope)
    }
    val syncOperations = remember(accountState) {
        SessionAccountSyncOperations(accountState)
    }
    val syncController = remember(syncOperations, scope) {
        AccountSyncState(syncOperations, scope)
    }
    DisposableEffect(signInCoordinator, identityProvider, accountState, startupState, consent, syncController) {
        onDispose {
            try {
                startupState.dispose()
            } catch (_: Exception) {
            }
            try {
                signInCoordinator.close()
            } catch (_: Exception) {
            }
            try {
                (identityProvider as? AndroidGoogleIdentityProvider)?.close()
            } catch (_: Exception) {
            }
            try {
                consent.close()
            } catch (_: Exception) {
            }
            try {
                accountState.dispose()
            } catch (_: Exception) {
            }
            try {
                syncController.dispose()
            } catch (_: Exception) {
            }
        }
    }
    AccountStartupApp(accountState, startupState, consent, syncController, serverConnectionState)
}

/**
 * Controlled root presentation for real-Room tests. Starts the state once and
 * renders features only through the existing account root's synchronous exact
 * binding. No test-only static override or global credential routing.
 */
@Composable
internal fun AccountStartupApp(
    accountState: AccountGymAppState,
    startupState: AccountStartupState,
    consent: AccountImportConsent,
    syncState: AccountSyncState? = null,
    serverConnectionState: ServerConnectionState? = null,
) {
    DisposableEffect(startupState) {
        startupState.start()
        onDispose {
            try {
                startupState.dispose()
            } catch (_: Exception) {
            }
        }
    }
    DisposableEffect(syncState) {
        syncState?.start()
        onDispose {
            try {
                syncState?.dispose()
            } catch (_: Exception) {
            }
        }
    }
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    DisposableEffect(activity?.lifecycle, syncState) {
        val lifecycle = activity?.lifecycle
        if (syncState == null) {
            return@DisposableEffect onDispose {}
        }
        if (lifecycle == null) {
            syncState.setForeground(false)
            return@DisposableEffect onDispose {
                syncState.setForeground(false)
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> syncState.setForeground(true)
                Lifecycle.Event.ON_STOP -> syncState.setForeground(false)
                Lifecycle.Event.ON_DESTROY -> syncState.setForeground(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        syncState.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            try {
                lifecycle.removeObserver(observer)
            } catch (_: Exception) {
            }
            syncState.setForeground(false)
        }
    }
    val authority = accountState.sessionCoordinator.state.collectAsState().value
    val ui by startupState.uiState.collectAsState()
    val syncUi = syncState?.uiState?.collectAsState()?.value
    val retryOwner = when (authority) {
        is AppSessionState.Private -> authority.room
        is AppSessionState.Active -> authority.session
        else -> null
    }
    val retryAtMillis = when (authority) {
        is AppSessionState.Private -> authority.retryAtMillis
        is AppSessionState.Active -> syncUi?.retryAtMillis
        else -> null
    }
    val retryEnabled = !ui.busy && when (authority) {
        is AppSessionState.Private -> ui.error == AccountStartupError.PREPARATION_PAUSED &&
            authority.room?.phase == RecoveryRoomPhase.PREPARING
        is AppSessionState.Active -> syncUi?.busy == false && syncUi.error == null && syncUi.recovery == null &&
            authority.deliveryStatus != AppSessionDeliveryStatus.REAUTHENTICATION_REQUIRED
        else -> false
    }
    val retrySeconds = accountRetrySeconds(
        retryOwner,
        retryAtMillis,
        retryEnabled,
        accountState::wallTimeMillis,
    ) {
        // A countdown is advisory. The operation still rereads authority and the durable head.
        when (val live = accountState.sessionCoordinator.state.value) {
            is AppSessionState.Private -> if (live.room == retryOwner && live.retryAtMillis == retryAtMillis &&
                startupState.uiState.value.error == AccountStartupError.PREPARATION_PAUSED
            ) {
                startupState.continuePreparation()
            }
            is AppSessionState.Active -> if (live.session === retryOwner &&
                syncState?.uiState?.value?.retryAtMillis == retryAtMillis
            ) {
                syncState?.syncNow()
            }
            else -> Unit
        }
    }
    val reconnections = serverConnectionState?.reconnections?.collectAsState()?.value ?: 0L
    val recoveryLifecycle = LocalLifecycleOwner.current.lifecycle
    val recoveryLifecycleState = recoveryLifecycle.currentStateAsState().value
    var seenReconnections by remember(serverConnectionState) { mutableStateOf(reconnections) }
    var reconnectOwner by remember(accountState, serverConnectionState) { mutableStateOf<Any?>(null) }
    LaunchedEffect(reconnections, retryOwner, ui.busy, recoveryLifecycleState) {
        if (reconnections > seenReconnections) {
            seenReconnections = reconnections
            reconnectOwner = retryOwner
        }
        if (reconnectOwner != retryOwner) reconnectOwner = null
        if (ui.busy || reconnectOwner == null || !recoveryLifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            return@LaunchedEffect
        }
        val owner = reconnectOwner
        reconnectOwner = null
        // Recheck the live owner; an old probe never grants access or follows a replacement account.
        when (val live = accountState.sessionCoordinator.state.value) {
            is AppSessionState.Active -> if (live.session === owner) syncState?.connectionRestored(live.session)
            is AppSessionState.Private -> if (live.room == owner && live.room?.phase == RecoveryRoomPhase.PREPARING &&
                startupState.uiState.value.error == AccountStartupError.PREPARATION_PAUSED
            ) {
                startupState.continuePreparation(connectionRestored = true)
            }
            else -> Unit
        }
    }
    LaunchedEffect(syncUi?.successfulSyncs) {
        if ((syncUi?.successfulSyncs ?: 0L) > 0L) startupState.onSyncSucceeded()
    }
    LaunchedEffect(syncState, serverConnectionState, syncUi?.connectionCheckRequests) {
        if ((syncUi?.connectionCheckRequests ?: 0L) > 0L) {
            serverConnectionState?.refreshAfterSyncFailure()
        }
    }
    val binding = accountState.visibleBinding()
    val selected = if (authority is AppSessionState.Active &&
        binding != null &&
        authority.session === binding.handle
    ) {
        binding
    } else {
        null
    }
    val pendingDecision by consent.pending.collectAsState()
    if (selected != null) {
        GymApp(
            accountState = accountState,
            serverConnectionState = serverConnectionState,
            settingsAccountContent = {
                AccountSettingsContent(
                    uiState = ui,
                    connectionStatus = serverConnectionState?.status?.collectAsState()?.value
                        ?: ServerConnectionStatus.NOT_CHECKED,
                    signInAvailable = startupState.signInAvailable,
                    deliveryStatus = (authority as AppSessionState.Active).deliveryStatus,
                    onReauthenticate = { startupState.signIn() },
                    onCancel = { startupState.cancel() },
                    syncState = syncState,
                    retrySeconds = retrySeconds,
                )
            },
        )
        ImportWarningDialog(pending = pendingDecision, consent = consent)
        return
    }

    StartupGate(
        authority = authority,
        uiState = ui,
        configurationAvailable = startupState.configurationAvailable,
        signInAvailable = startupState.signInAvailable,
        pendingDecision = pendingDecision,
        consent = consent,
        onSignIn = { startupState.signIn() },
        onContinue = { startupState.continuePreparation() },
        onCancel = { startupState.cancel() },
        retrySeconds = retrySeconds,
        serverConnectionState = serverConnectionState,
    )
    ImportWarningDialog(pending = pendingDecision, consent = consent)
}

@Composable
private fun UnconfiguredAccountGate() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("account-privacy-gate")
            .padding(bottom = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .testTag("account-configuration-unavailable")
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Sign-in unavailable", style = MaterialTheme.typography.titleMedium)
            Text("Service configuration is unavailable. No account features are available.")
        }
    }
}

@Composable
private fun StartupGate(
    authority: AppSessionState,
    uiState: AccountStartupUiState,
    configurationAvailable: Boolean,
    signInAvailable: Boolean,
    pendingDecision: AccountImportConsentDecision?,
    consent: AccountImportConsent,
    onSignIn: () -> Unit,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
    retrySeconds: Long?,
    serverConnectionState: ServerConnectionState?,
) {
    val room = (authority as? AppSessionState.Private)?.room
    val reason = (authority as? AppSessionState.Private)?.reason
    val isInitialSignIn = authority is AppSessionState.Private &&
        (reason == AppSessionReason.SIGN_IN_REQUIRED ||
            (room != null && room.phase == RecoveryRoomPhase.SIGNED_OUT))
    // Finish signing in resumes the saved activation without acquiring a new grant.
    val canContinue = authority is AppSessionState.Private &&
        room != null && room.phase == RecoveryRoomPhase.PREPARING
    val waitingForRetry = retrySeconds != null
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("account-privacy-gate")
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                !configurationAvailable || reason == AppSessionReason.CONFIGURATION_UNAVAILABLE -> {
                    Column(
                        modifier = Modifier.testTag("account-configuration-unavailable"),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Sign-in unavailable", style = MaterialTheme.typography.titleMedium)
                        Text("Service configuration is unavailable. No account features are available.")
                    }
                }
                isInitialSignIn -> {
                    Text("Sign in required", style = MaterialTheme.typography.titleMedium)
                    Text("Sign in to access your workouts.")
                    if (signInAvailable) {
                        TextButton(
                            onClick = onSignIn,
                            enabled = !uiState.busy,
                            modifier = Modifier.testTag("account-sign-in"),
                        ) {
                            Text("Sign in")
                        }
                    } else {
                        Text("Foreground sign-in is unavailable on this install.")
                    }
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.testTag("account-cancel"),
                    ) {
                        Text("Cancel")
                    }
                }
                authority is AppSessionState.Private -> {
                    Column(
                        modifier = Modifier.testTag("account-preparation"),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            if (waitingForRetry) "Signed in" else "Preparing account",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            if (waitingForRetry) "Waiting to sync your saved changes. You do not need to sign in again."
                            else "Your account is being prepared. Your data stays private until activation completes.",
                        )
                    }
                    if (canContinue) {
                        TextButton(
                            onClick = onContinue,
                            enabled = !uiState.busy && !waitingForRetry,
                            modifier = Modifier.testTag("account-continue-preparation"),
                        ) {
                            Text("Finish signing in")
                        }
                        Text(
                            if (waitingForRetry) {
                                accountRetryText(checkNotNull(retrySeconds))
                            } else if (signInAvailable) {
                                "Resume your saved sign-in, or sign in again to get a new session for the same account."
                            } else {
                                "Resume your saved sign-in."
                            },
                        )
                    }
                    if (signInAvailable) {
                        TextButton(
                            onClick = onSignIn,
                            enabled = !uiState.busy && !waitingForRetry,
                            modifier = Modifier.testTag("account-sign-in"),
                        ) {
                            Text("Sign in")
                        }
                    } else {
                        Text("Foreground sign-in is unavailable on this install.")
                    }
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.testTag("account-cancel"),
                    ) {
                        Text("Cancel")
                    }
                }
                else -> {
                    Text("Unavailable")
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.testTag("account-cancel"),
                    ) {
                        Text("Cancel")
                    }
                }
            }
            if (uiState.busy) {
                CircularProgressIndicator()
            }
            uiState.error?.takeUnless { waitingForRetry }?.let { error ->
                Text(
                    text = fixedStartupError(error),
                    modifier = Modifier.testTag("account-error"),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            uiState.importProgress?.let { progress ->
                ImportProgressText(progress)
            }
            if (waitingForRetry && serverConnectionState != null) {
                ServerConnectionSection(serverConnectionState)
            }
            if (pendingDecision != null) {
                Text("An import confirmation is waiting.")
            }
        }
    }
}

@Composable
private fun ImportProgressText(progress: LegacyImportProgress) {
    val phase = when (progress.phase) {
        LegacyImportProgressPhase.BLOCKED -> "Blocked"
        LegacyImportProgressPhase.CAPTURED -> "Captured"
        LegacyImportProgressPhase.SETTLED -> "Settled"
    }
    Text(
        "Original data import: ${progress.acceptedCount} of ${progress.importCount} accepted. " +
            "State: $phase.",
    )
    if (progress.phase == LegacyImportProgressPhase.BLOCKED) {
        Text("Import cannot proceed automatically.")
    } else {
        Text("Activation still required.")
    }
}

@Composable
private fun ImportWarningDialog(
    pending: AccountImportConsentDecision?,
    consent: AccountImportConsent,
) {
    val decision = pending ?: return
    GymDialog(
        onDismissRequest = { consent.answer(decision, false) },
        modifier = Modifier.testTag("account-import-warning"),
        title = "Replace cloud workout?",
        text = {
            Text(
                "Importing your original local active workout may replace the cloud active workout. " +
                    "Already accepted remote imports cannot be undone by cancellation.",
            )
        },
        confirmAction = GymDialogAction(
            text = "Import",
            onClick = { consent.answer(decision, true) },
            modifier = Modifier.testTag("account-import-confirm"),
        ),
        dismissAction = GymDialogAction(
            text = "Cancel",
            onClick = { consent.answer(decision, false) },
            modifier = Modifier.testTag("account-import-cancel"),
        ),
    )
}

private fun fixedStartupError(error: AccountStartupError): String =
    when (error) {
        AccountStartupError.UNAVAILABLE -> "Unavailable. Your data stays private."
        AccountStartupError.SIGN_IN_FAILED -> "Sign-in failed. Your data stays private."
        AccountStartupError.PREPARATION_PAUSED -> "Sign-in paused. Finish signing in when ready."
        AccountStartupError.IDENTITY_MISMATCH -> "Account mismatch. Your data stays private."
        AccountStartupError.EDITOR_OPEN -> "Finish Save or Discard in the open editor, or cancel this account action."
        AccountStartupError.CANCELLED -> "Cancelled."
    }
