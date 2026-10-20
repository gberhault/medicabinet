package com.medicabinet.app.features.scan

import androidx.lifecycle.ViewModel
import com.medicabinet.app.app.AppEnvironment
import com.medicabinet.app.features.scan.camera.VisibleCodes
import com.medicabinet.core.Box
import com.medicabinet.core.ExpiryDate
import com.medicabinet.core.Location
import com.medicabinet.core.RemovalReason
import com.medicabinet.core.RepositoryError
import com.medicabinet.core.RepositoryException
import com.medicabinet.core.cabinet.RemovalOutcome
import com.medicabinet.core.cabinet.ScanRemoval
import com.medicabinet.core.scan.BoxDraft
import com.medicabinet.core.scan.GS1Parser
import com.medicabinet.core.scan.IntakeError
import com.medicabinet.core.scan.IntakeException
import com.medicabinet.core.scan.IntakeField
import com.medicabinet.core.scan.IntakeOutcome
import com.medicabinet.core.scan.ScanDebouncer
import com.medicabinet.core.scan.ScanIntake
import com.medicabinet.core.scan.ScanParseException
import com.medicabinet.core.scan.ScannedCode
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class CameraState {
    /** Before the screen has looked at the permission: nothing is shown and no camera is built. */
    CHECKING,
    NEEDS_PERMISSION,
    DENIED,
    UNSUPPORTED,
    STARTING,
    SCANNING,
    FAILED
}

enum class FeedbackEvent {
    /** A box was added. */
    ADDED,

    /** A box was taken out of the cabinet (Remove mode): a different haptic than an addition. */
    REMOVED,

    /** A scan was refused or failed (duplicate, unreadable code, error). */
    REJECTED,

    /** The user is asked something (completion, another box). */
    ATTENTION
}

/** A haptic (and optional sound) to play: the screen performs it, the view model only decides. */
data class FeedbackRequest(val event: FeedbackEvent, val sound: Boolean)

/** Something a box cannot be saved without: asked while the box is in hand. Cancel adds nothing. */
data class Completion(
    val draft: BoxDraft,
    val missing: Set<IntakeField>,
    val expiryUnreadable: Boolean,
    val id: UUID = UUID.randomUUID()
)

/** "Another box of X?" — does not stop the camera; the next scan replaces it. */
data class Another(val draft: BoxDraft, val thenMissing: Set<IntakeField>, val expiryUnreadable: Boolean)

sealed interface Notice {
    data class Added(val box: Box) : Notice

    data class AlreadyInCabinet(val box: Box, val locationName: String) : Notice

    data class RemovedEarlier(val box: Box) : Notice

    data class PutBack(val box: Box) : Notice

    data class Deleted(val box: Box) : Notice

    /** Remove mode: the box is out of the cabinet (stock movement written), with Undo. */
    data class Removed(val box: Box, val reason: RemovalReason, val locationName: String) : Notice

    data class AlreadyRemoved(val box: Box) : Notice

    data object NotInCabinet : Notice

    data object Unrecognized : Notice

    data object Failed : Notice
}

data class Summary(
    val added: Int,
    val unknownExpiry: Int,
    val mode: ScanMode = ScanMode.ADD,
    val usedUp: Int = 0,
    val thrownAway: Int = 0
)

/** Several boxes match a scan in Remove mode: the user picks one. Ordered: the first is pre-selected. */
data class Candidates(val boxes: List<Box>, val id: UUID = UUID.randomUUID())

enum class ManualError { INVALID_CODE, INVALID_NAME, FAILED }

data class ScanUiState(
    val camera: CameraState = CameraState.CHECKING,
    val session: ScanSession = ScanSession(),
    val locations: List<Location> = emptyList(),
    val sessionLocation: Location? = null,
    val notice: Notice? = null,
    val another: Another? = null,
    val completion: Completion? = null,
    val summary: Summary? = null,
    val mode: ScanMode = ScanMode.ADD,
    val removalReason: RemovalReason = RemovalReason.USED_UP,
    /** The boxes taken out in the current Remove session (cleared by Done and by switching back to Add). */
    val removalSession: ScanSession = ScanSession(),
    val candidates: Candidates? = null,
    val soundEnabled: Boolean = false,
    /** Bumped by Retry; the camera view is rebuilt when it changes. */
    val attempt: Int = 0
)

/**
 * Presentation state of the Scan tab: camera state, the scan loop, prompts, the session. Persistence goes through
 * [ScanIntake] and the repositories; the camera and haptics through [ScanServices] and the screen.
 */
@Suppress("TooManyFunctions") // one entry point per user action of the scan screen
class ScanViewModel(environment: AppEnvironment, private val services: ScanServices) : ViewModel() {
    private val intake = ScanIntake(environment.boxes, services.lookup, environment.clock)
    private val removal = ScanRemoval(environment.boxes, environment.clock)
    private val locationRepository = environment.locations
    private val boxes = environment.boxes
    private val cabinetRevision = environment.revision
    private var seenRevision = cabinetRevision.value
    private var debouncer = ScanDebouncer()
    private val reportedUnrecognized = mutableSetOf<String>()

    private val mutableState = MutableStateFlow(
        ScanUiState(
            soundEnabled = services.preferences.soundEnabled,
            removalReason = services.preferences.removalReason
        )
    )
    val state: StateFlow<ScanUiState> = mutableState.asStateFlow()

    private val mutableFeedback = MutableSharedFlow<FeedbackRequest>(
        extraBufferCapacity = FEEDBACK_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val feedback: SharedFlow<FeedbackRequest> = mutableFeedback.asSharedFlow()

    // region Camera

    /** The screen's answer about the camera permission. Never restarts a camera that is already scanning. */
    fun onCameraAccess(access: CameraAccess) {
        mutableState.update { current ->
            val camera = when {
                !services.hasCamera -> CameraState.UNSUPPORTED
                access == CameraAccess.NOT_DETERMINED -> CameraState.NEEDS_PERMISSION
                access == CameraAccess.DENIED -> CameraState.DENIED
                current.camera in setOf(
                    CameraState.CHECKING,
                    CameraState.NEEDS_PERMISSION,
                    CameraState.DENIED
                ) -> CameraState.STARTING
                else -> current.camera
            }
            current.copy(camera = camera)
        }
    }

    /** The camera delivered its first frames. */
    fun onPreviewStarted() = mutableState.update {
        if (it.camera == CameraState.STARTING) it.copy(camera = CameraState.SCANNING) else it
    }

    fun onCameraFailed() = mutableState.update { it.copy(camera = CameraState.FAILED) }

    fun retry() = mutableState.update { it.copy(camera = CameraState.STARTING, attempt = it.attempt + 1) }

    fun permissionRequested() {
        services.preferences.cameraRequested = true
    }

    val cameraRequestedBefore: Boolean get() = services.preferences.cameraRequested

    // endregion

    // region Locations and preferences

    /** The cabinet was replaced (an import or a restore) since this model last looked: the session cannot go on. */
    val revision: StateFlow<Long> get() = cabinetRevision

    /**
     * Reads the locations. When the whole cabinet was replaced meanwhile (I7, design §1.6), the session ends first:
     * its boxes no longer exist, and a session location that is gone falls back to the default (I10).
     */
    suspend fun load() {
        if (cabinetRevision.value != seenRevision) {
            seenRevision = cabinetRevision.value
            endSession()
        }
        val loaded = try {
            locationRepository.all()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") failure: Exception) {
            return
        }
        mutableState.update { current ->
            val preferred = services.preferences.lastLocationId?.let { id -> loaded.firstOrNull { it.id == id } }
            val kept = current.sessionLocation?.let { c -> loaded.firstOrNull { it.id == c.id } }
            current.copy(
                locations = loaded,
                sessionLocation = kept ?: preferred ?: loaded.firstOrNull { it.isDefault } ?: loaded.firstOrNull()
            )
        }
    }

    fun selectLocation(id: UUID) {
        val location = state.value.locations.firstOrNull { it.id == id } ?: return
        mutableState.update { it.copy(sessionLocation = location) }
        services.preferences.lastLocationId = id
    }

    fun locationName(id: UUID): String = state.value.locations.firstOrNull { it.id == id }?.name.orEmpty()

    fun setSound(enabled: Boolean) {
        services.preferences.soundEnabled = enabled
        mutableState.update { it.copy(soundEnabled = enabled) }
    }

    // endregion

    // region Scanning

    suspend fun handle(snapshot: VisibleCodes) {
        // Sightings always refresh the debouncer, even while a prompt is open, so a box still in front of the
        // camera is not counted again when the prompt closes.
        val scans = debouncer.observe(snapshot.payloads, snapshot.instant)
        for (payload in scans) {
            val current = state.value
            if (current.completion != null || current.candidates != null || current.summary != null) return
            process(payload)
        }
    }

    private suspend fun process(payload: String) {
        mutableState.update { it.copy(another = null) }
        val code = parse(payload) ?: return
        if (state.value.mode == ScanMode.REMOVE) {
            processRemoval(code)
            return
        }
        try {
            apply(intake.evaluate(code, state.value.session.gtins))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") failure: Exception) {
            show(Notice.Failed, FeedbackEvent.REJECTED)
        }
    }

    private fun parse(payload: String): ScannedCode? = try {
        GS1Parser.parse(payload)
    } catch (@Suppress("SwallowedException") failure: ScanParseException) {
        if (reportedUnrecognized.add(payload)) show(Notice.Unrecognized, FeedbackEvent.REJECTED)
        null
    }

    private suspend fun apply(outcome: IntakeOutcome) {
        when (outcome) {
            is IntakeOutcome.Ready -> commit(outcome.draft)
            is IntakeOutcome.NeedsCompletion -> {
                mutableState.update {
                    it.copy(completion = Completion(outcome.draft, outcome.missing, outcome.expiryUnreadable))
                }
                play(FeedbackEvent.ATTENTION)
            }
            is IntakeOutcome.ConfirmAnother -> {
                mutableState.update {
                    it.copy(another = Another(outcome.draft, outcome.thenMissing, outcome.expiryUnreadable))
                }
                play(FeedbackEvent.ATTENTION)
            }
            is IntakeOutcome.AlreadyInCabinet ->
                show(Notice.AlreadyInCabinet(outcome.box, locationName(outcome.box.locationId)), FeedbackEvent.REJECTED)
            is IntakeOutcome.RemovedEarlier -> show(Notice.RemovedEarlier(outcome.box), FeedbackEvent.REJECTED)
        }
    }

    /** Saves a draft in the session location, then confirms (card, haptic, optional sound, TalkBack). */
    suspend fun commit(draft: BoxDraft) {
        val location = state.value.sessionLocation
        if (location == null) {
            show(Notice.Failed, FeedbackEvent.REJECTED)
            return
        }
        try {
            val box = intake.add(draft, location.id)
            mutableState.update { it.copy(session = it.session.add(box)) }
            show(Notice.Added(box), FeedbackEvent.ADDED)
        } catch (failure: RepositoryException) {
            if (failure.error == RepositoryError.DUPLICATE_SERIAL) showDuplicate(draft) else fail()
        } catch (@Suppress("SwallowedException") failure: IntakeException) {
            fail()
        }
    }

    private suspend fun showDuplicate(draft: BoxDraft) {
        val gtin = draft.gtin
        val serial = draft.serial
        val existing = if (gtin != null && serial != null) runCatching { boxes.box(gtin, serial) }.getOrNull() else null
        if (existing != null) {
            show(Notice.AlreadyInCabinet(existing, locationName(existing.locationId)), FeedbackEvent.REJECTED)
        } else {
            fail()
        }
    }

    private fun fail() = show(Notice.Failed, FeedbackEvent.REJECTED)

    // endregion

    // region Prompts

    suspend fun confirmCompletion(name: String?, expiry: ExpiryDate?) {
        val current = state.value.completion ?: return
        var draft = current.draft
        if (IntakeField.NAME in current.missing) draft = draft.copy(name = name)
        if (IntakeField.EXPIRY in current.missing) draft = draft.copy(expiry = expiry)
        mutableState.update { it.copy(completion = null) }
        commit(draft)
    }

    fun cancelCompletion() = mutableState.update { it.copy(completion = null) }

    suspend fun confirmAnother() {
        val pending = state.value.another ?: return
        mutableState.update { it.copy(another = null) }
        if (pending.thenMissing.isEmpty()) {
            commit(pending.draft)
        } else {
            mutableState.update {
                it.copy(completion = Completion(pending.draft, pending.thenMissing, pending.expiryUnreadable))
            }
        }
    }

    fun dismissAnother() = mutableState.update { it.copy(another = null) }

    fun dismissNotice() = mutableState.update { it.copy(notice = null) }

    suspend fun putBack(box: Box) {
        val location = state.value.sessionLocation ?: return
        try {
            val restored = intake.putBack(box, location.id)
            mutableState.update { it.copy(session = it.session.add(restored)) }
            show(Notice.PutBack(restored), FeedbackEvent.ADDED)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") failure: Exception) {
            fail()
        }
    }

    // endregion

    // region Session list

    suspend fun delete(box: Box) {
        try {
            intake.delete(box.id)
            mutableState.update { it.copy(session = it.session.remove(box.id)) }
            show(Notice.Deleted(box), null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") failure: Exception) {
            fail()
        }
    }

    suspend fun undoDelete(box: Box) {
        try {
            intake.undoDelete(box.id)
            val restored = boxes.box(box.id)
            mutableState.update {
                it.copy(
                    session = if (restored !=
                        null
                    ) {
                        it.session.add(restored)
                    } else {
                        it.session
                    },
                    notice = null
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") failure: Exception) {
            fail()
        }
    }

    /** Saves an inline correction. The expiry can go back to unknown but is never guessed. */
    suspend fun update(box: Box, name: String, expiry: ExpiryDate?, locationId: UUID): Boolean {
        val trimmed = name.trim()
        if (trimmed.codePointCount(0, trimmed.length) !in 1..ScanIntake.MAX_NAME_LENGTH) return false
        return try {
            boxes.update(box.copy(product = box.product.copy(name = trimmed), expiry = expiry, locationId = locationId))
            boxes.box(box.id)?.let { saved -> mutableState.update { it.copy(session = it.session.replace(saved)) } }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") failure: Exception) {
            fail()
            false
        }
    }

    // endregion

    // region Manual entry

    /** Adds a box typed by hand: no scanner involved, works without camera permission. Returns the error, if any. */
    suspend fun addManually(code: String, name: String, expiry: ExpiryDate?, locationId: UUID): ManualError? = try {
        val draft = intake.manualDraft(code, name).copy(expiry = expiry)
        val box = intake.add(draft, locationId)
        mutableState.update { it.copy(session = it.session.add(box)) }
        show(Notice.Added(box), FeedbackEvent.ADDED)
        null
    } catch (failure: IntakeException) {
        if (failure.error == IntakeError.INVALID_CODE) ManualError.INVALID_CODE else ManualError.INVALID_NAME
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") failure: Exception) {
        ManualError.FAILED
    }

    // endregion

    // region Remove mode

    /** The tab was opened: Remove is never remembered (R1). */
    fun resetMode() = setMode(ScanMode.ADD)

    fun setMode(mode: ScanMode) = mutableState.update {
        if (mode == it.mode && it.candidates == null) {
            it
        } else {
            it.copy(
                mode = mode,
                candidates = null,
                another = null,
                notice = null,
                // Leaving Remove ends the Remove session; what was removed stays removed.
                removalSession = if (mode == ScanMode.ADD) ScanSession() else it.removalSession
            )
        }
    }

    fun setRemovalReason(reason: RemovalReason) {
        services.preferences.removalReason = reason
        mutableState.update { it.copy(removalReason = reason) }
    }

    private suspend fun processRemoval(code: ScannedCode) {
        try {
            when (val outcome = removal.handle(code, state.value.removalReason)) {
                is RemovalOutcome.Removed -> confirmRemoval(outcome.box)
                is RemovalOutcome.Choose -> {
                    mutableState.update { it.copy(candidates = Candidates(outcome.candidates)) }
                    play(FeedbackEvent.ATTENTION)
                }
                is RemovalOutcome.AlreadyRemoved -> show(Notice.AlreadyRemoved(outcome.box), FeedbackEvent.REJECTED)
                RemovalOutcome.NotInCabinet -> show(Notice.NotInCabinet, FeedbackEvent.REJECTED)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") failure: Exception) {
            fail()
        }
    }

    /** The user picked one of the candidates. */
    suspend fun removeCandidate(id: UUID) {
        mutableState.update { it.copy(candidates = null) }
        try {
            confirmRemoval(removal.remove(id, state.value.removalReason))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") failure: Exception) {
            fail()
        }
    }

    fun cancelCandidates() = mutableState.update { it.copy(candidates = null) }

    suspend fun undoRemoval(box: Box) {
        try {
            removal.undo(box.id)
            mutableState.update { it.copy(removalSession = it.removalSession.remove(box.id), notice = null) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") failure: Exception) {
            fail()
        }
    }

    private fun confirmRemoval(box: Box) {
        val reason = box.removal?.reason ?: state.value.removalReason
        mutableState.update { it.copy(removalSession = it.removalSession.add(box)) }
        show(Notice.Removed(box, reason, locationName(box.locationId)), FeedbackEvent.REMOVED)
    }

    // endregion

    // region Done

    fun finish() = mutableState.update {
        val removed = it.removalSession.boxes
        it.copy(
            summary = Summary(
                added = it.session.count,
                unknownExpiry = it.session.unknownExpiryCount,
                mode = it.mode,
                usedUp = removed.count { box -> box.removal?.reason == RemovalReason.USED_UP },
                thrownAway = removed.count { box -> box.removal?.reason == RemovalReason.THROWN_AWAY }
            )
        )
    }

    /** Forgets everything the scan session held; nothing is written. */
    private fun endSession() {
        debouncer = ScanDebouncer()
        reportedUnrecognized.clear()
        mutableState.update {
            it.copy(
                session = ScanSession(),
                removalSession = ScanSession(),
                notice = null,
                another = null,
                completion = null,
                candidates = null,
                summary = null
            )
        }
    }

    fun closeSummary() {
        debouncer = ScanDebouncer()
        reportedUnrecognized.clear()
        mutableState.update {
            if (it.summary?.mode == ScanMode.REMOVE) {
                it.copy(summary = null, removalSession = ScanSession(), notice = null, another = null)
            } else {
                it.copy(summary = null, session = ScanSession(), notice = null, another = null)
            }
        }
    }

    // endregion

    private fun show(notice: Notice, event: FeedbackEvent?) {
        mutableState.update { it.copy(notice = notice) }
        if (event != null) play(event)
    }

    private fun play(event: FeedbackEvent) {
        mutableFeedback.tryEmit(FeedbackRequest(event, state.value.soundEnabled))
    }

    private companion object {
        const val FEEDBACK_BUFFER = 8
    }
}
