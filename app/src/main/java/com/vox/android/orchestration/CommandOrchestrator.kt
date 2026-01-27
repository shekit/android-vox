package com.vox.android.orchestration

import android.util.Log
import com.vox.android.core.interfaces.ActionDecisionResult
import com.vox.android.core.interfaces.ActionDecisionService
import com.vox.android.core.interfaces.ActionExecutor
import com.vox.android.core.interfaces.DeviceStateProvider
import com.vox.android.core.models.Action
import com.vox.android.core.models.Command
import com.vox.android.core.models.CommandRecord
import com.vox.android.core.models.CommandResult
import com.vox.android.core.models.CommandStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates command execution using coroutines.
 * This is the heart of the execution loop, independent of UI.
 */
class CommandOrchestrator(
    private val stateProvider: DeviceStateProvider,
    private val actionExecutor: ActionExecutor,
    private val decisionService: ActionDecisionService,
    private val config: OrchestratorConfig = OrchestratorConfig()
) {
    companion object {
        private const val TAG = "Vox"
    }

    private val _executionState = MutableStateFlow<ExecutionState>(ExecutionState.Idle)
    val executionState: StateFlow<ExecutionState> = _executionState.asStateFlow()

    private val _isActive = AtomicBoolean(false)
    val isActive: Boolean get() = _isActive.get()

    private var currentJob: Job? = null

    /**
     * Execute a command. Returns a Flow that emits state updates.
     */
    fun execute(command: Command, scope: CoroutineScope): Flow<ExecutionState> = flow {
        if (!_isActive.compareAndSet(false, true)) {
            emit(ExecutionState.Failed(command, "Another command is already executing", emptyList()))
            return@flow
        }

        val startTime = System.currentTimeMillis()
        val records = mutableListOf<CommandRecord>()
        var previousUiHash = 0

        try {
            emit(ExecutionState.Starting(command))
            _executionState.value = ExecutionState.Starting(command)

            var stepNumber = 1

            while (stepNumber <= config.maxSteps && _isActive.get()) {
                // Get current state
                emit(ExecutionState.Analyzing(command, stepNumber))
                _executionState.value = ExecutionState.Analyzing(command, stepNumber)

                val includeScreenshot = stepNumber > 1 // Skip screenshot on step 1 (still in android-vox)
                val state = stateProvider.getDeviceState(includeScreenshot)

                if (state.uiTree.isEmpty()) {
                    val result = CommandResult.failed(
                        command, records, "No UI tree available",
                        System.currentTimeMillis() - startTime
                    )
                    emit(ExecutionState.Failed(command, "No UI tree available", records))
                    _executionState.value = ExecutionState.Failed(command, "No UI tree available", records)
                    return@flow
                }

                val currentUiHash = state.contentHash()
                val uiChanged = previousUiHash == 0 || currentUiHash != previousUiHash
                Log.d(TAG, "Step $stepNumber - UI hash: $currentUiHash, changed: $uiChanged")

                // Decide action
                val decision = decisionService.decideAction(command.text, state, records, uiChanged)

                when (decision) {
                    is ActionDecisionResult.Success -> {
                        val action = decision.action
                        Log.d(TAG, "Step $stepNumber - Decided: ${action.toCommandString()}")

                        // Check if done
                        if (action is Action.Done) {
                            val result = CommandResult.completed(command, records, System.currentTimeMillis() - startTime)
                            emit(ExecutionState.Completed(command, result))
                            _executionState.value = ExecutionState.Completed(command, result)
                            return@flow
                        }

                        // Execute action
                        emit(ExecutionState.Executing(command, stepNumber, action))
                        _executionState.value = ExecutionState.Executing(command, stepNumber, action)

                        delay(config.preActionDelayMs)

                        val result = actionExecutor.execute(action)
                        Log.d(TAG, "Step $stepNumber - Result: ${result.toLogString()}")

                        // Wait for UI change
                        emit(ExecutionState.WaitingForUiChange(command, stepNumber, action))
                        _executionState.value = ExecutionState.WaitingForUiChange(command, stepNumber, action)

                        delay(config.uiChangeWaitMs)

                        // Get updated UI hash
                        val newState = stateProvider.getDeviceState(false)
                        val newUiHash = newState.contentHash()
                        val actionUiChanged = newUiHash != currentUiHash

                        // Record this step
                        val record = CommandRecord(
                            stepNumber = stepNumber,
                            action = action,
                            result = result,
                            uiChanged = actionUiChanged,
                            uiHashBefore = currentUiHash,
                            uiHashAfter = newUiHash
                        )
                        records.add(record)

                        previousUiHash = newUiHash
                        stepNumber++
                    }

                    is ActionDecisionResult.Failure -> {
                        Log.e(TAG, "Decision failed: ${decision.error}")
                        val result = CommandResult.failed(
                            command, records, decision.error,
                            System.currentTimeMillis() - startTime
                        )
                        emit(ExecutionState.Failed(command, decision.error, records))
                        _executionState.value = ExecutionState.Failed(command, decision.error, records)
                        return@flow
                    }
                }
            }

            // Max steps reached
            if (stepNumber > config.maxSteps) {
                val result = CommandResult.maxStepsReached(command, records, System.currentTimeMillis() - startTime)
                emit(ExecutionState.Failed(command, "Maximum steps (${config.maxSteps}) reached", records))
                _executionState.value = ExecutionState.Failed(command, "Maximum steps reached", records)
            }

        } catch (e: CancellationException) {
            Log.d(TAG, "Command execution cancelled")
            emit(ExecutionState.Cancelled(command, records))
            _executionState.value = ExecutionState.Cancelled(command, records)
        } catch (e: Exception) {
            Log.e(TAG, "Command execution error", e)
            emit(ExecutionState.Failed(command, "Error: ${e.message}", records))
            _executionState.value = ExecutionState.Failed(command, "Error: ${e.message}", records)
        } finally {
            _isActive.set(false)
        }
    }.flowOn(Dispatchers.Main)

    /**
     * Cancel the current execution.
     */
    fun cancel() {
        _isActive.set(false)
        currentJob?.cancel()
        Log.d(TAG, "Execution cancelled")
    }

    /**
     * Reset to idle state.
     */
    fun reset() {
        cancel()
        _executionState.value = ExecutionState.Idle
    }
}

/**
 * Configuration for the orchestrator.
 */
data class OrchestratorConfig(
    /** Maximum number of steps before stopping */
    val maxSteps: Int = 20,
    /** Delay before executing action (ms) */
    val preActionDelayMs: Long = 500,
    /** Time to wait for UI change after action (ms) */
    val uiChangeWaitMs: Long = 1500
)
