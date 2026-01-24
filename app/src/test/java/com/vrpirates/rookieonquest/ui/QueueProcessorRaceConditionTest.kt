package com.vrpirates.rookieonquest.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * Unit tests for the Queue Processor race condition fix (Story 1.10).
 *
 * These tests verify that the queue processor correctly handles the race condition
 * where a task is added to Room DB but the StateFlow hasn't emitted it yet.
 *
 * The fix uses a Channel-based signaling mechanism (MainViewModel.kt:1189-1236):
 * - `taskAddedSignal` (Channel<Unit> with CONFLATED) signals when tasks are added
 * - `startQueueProcessor()` uses `select { }` with `onReceive` and `onTimeout` to wait
 *
 * TESTING STRATEGY:
 * This test isolates the synchronization pattern without Android dependencies.
 * The simulated logic mirrors MainViewModel.startQueueProcessor() lines 1189-1236.
 *
 * While an integration test with the actual ViewModel would be ideal, that would require:
 * - Mock Room Database (complex setup)
 * - Mock MainRepository with WorkManager dependencies
 * - Full Android test infrastructure (slower, more fragile)
 *
 * This unit test provides fast regression protection for the core synchronization logic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueueProcessorRaceConditionTest {

    // Simulated status enum (matches UI InstallTaskStatus)
    enum class TestTaskStatus {
        QUEUED, DOWNLOADING, EXTRACTING, INSTALLING, PAUSED, COMPLETED, FAILED
    }

    // Simulated task state
    data class TestTask(
        val releaseName: String,
        val status: TestTaskStatus
    )

    // Test fixtures
    private lateinit var taskAddedSignal: Channel<Unit>
    private val _installQueue = MutableStateFlow<List<TestTask>>(emptyList())
    private var processedTasks = mutableListOf<String>()

    @Before
    fun setup() {
        taskAddedSignal = Channel(Channel.CONFLATED)
        _installQueue.value = emptyList()
        processedTasks.clear()
    }

    /**
     * Simulates the FIXED queue processor logic from MainViewModel.kt:1189-1236.
     *
     * The actual implementation uses `select { }` with:
     * - `taskAddedSignal.onReceive` to wait for new task signals
     * - `onTimeout(500.milliseconds)` to check for genuinely empty queues
     *
     * This simulation mirrors that pattern to test the synchronization logic works correctly.
     */
    private fun CoroutineScope.startQueueProcessorFixed(): Job {
        return launch {
            while (isActive) {
                val nextTask = _installQueue.value.find { it.status == TestTaskStatus.QUEUED }

                if (nextTask != null) {
                    // Simulate processing the task
                    processedTasks.add(nextTask.releaseName)
                    // Update status to DOWNLOADING
                    _installQueue.update { list ->
                        list.map { if (it.releaseName == nextTask.releaseName) it.copy(status = TestTaskStatus.DOWNLOADING) else it }
                    }
                } else {
                    // Wait for signal or timeout
                    val signalReceived = select<Boolean> {
                        taskAddedSignal.onReceive { true }
                        onTimeout(500.milliseconds) { false }
                    }

                    if (signalReceived) {
                        delay(100) // Small delay for StateFlow propagation
                        continue
                    }

                    // Timeout - check if genuinely empty
                    if (_installQueue.value.none { it.status == TestTaskStatus.QUEUED }) {
                        break
                    }
                }
            }
        }
    }

    /**
     * Simulates the BUGGY queue processor logic (pre-fix, MainViewModel.kt:1172-1193).
     *
     * The bug was: the processor immediately exited if no QUEUED tasks were found,
     * without waiting for the StateFlow to emit newly added tasks.
     * This caused the race condition where users clicked Install but downloads never started.
     */
    private fun CoroutineScope.startQueueProcessorBuggy(): Job {
        return launch {
            while (isActive) {
                val nextTask = _installQueue.value.find { it.status == TestTaskStatus.QUEUED }
                if (nextTask == null) {
                    if (_installQueue.value.none { it.status == TestTaskStatus.QUEUED }) {
                        break // BUG: Exits immediately without waiting
                    }
                    delay(1000)
                    continue
                }
                // Simulate processing
                processedTasks.add(nextTask.releaseName)
                _installQueue.update { list ->
                    list.map { if (it.releaseName == nextTask.releaseName) it.copy(status = TestTaskStatus.DOWNLOADING) else it }
                }
            }
        }
    }

    // ========== Tests for the FIXED implementation ==========

    @Test
    fun `FIXED - processor waits for signal when task added concurrently`() = runTest {
        // Scenario: User clicks Install, processor starts, but StateFlow hasn't emitted yet

        // Start processor BEFORE task is visible in StateFlow (simulates race condition)
        val processorJob = backgroundScope.startQueueProcessorFixed()

        // Advance time to let processor start and check for tasks
        advanceTimeBy(100)

        // Now "add" the task (simulates Room insert completing)
        _installQueue.value = listOf(TestTask("game-v1.0", TestTaskStatus.QUEUED))

        // Send signal (simulates taskAddedSignal.trySend(Unit) in installGame())
        taskAddedSignal.trySend(Unit)

        // Advance time for processor to react to signal and process task
        advanceTimeBy(600)

        // Verify task was processed
        assertTrue("Task should have been processed", processedTasks.contains("game-v1.0"))
        assertEquals(TestTaskStatus.DOWNLOADING, _installQueue.value.first().status)

        processorJob.cancel()
    }

    @Test
    fun `FIXED - processor exits cleanly when queue is genuinely empty`() = runTest {
        // Start processor with empty queue
        val processorJob = backgroundScope.startQueueProcessorFixed()

        // Wait for timeout and exit
        advanceTimeBy(600)

        // Allow some time for job to complete
        runCurrent()

        // Processor should have exited without processing anything
        assertTrue("No tasks should have been processed", processedTasks.isEmpty())
        assertFalse("Processor job should complete", processorJob.isActive)
    }

    @Test
    fun `FIXED - processor handles multiple rapid task additions`() = runTest {
        val processorJob = backgroundScope.startQueueProcessorFixed()
        advanceTimeBy(100)

        // Rapidly add multiple tasks
        _installQueue.value = listOf(TestTask("game1", TestTaskStatus.QUEUED))
        taskAddedSignal.trySend(Unit)
        advanceTimeBy(200)
        runCurrent()

        _installQueue.update { it + TestTask("game2", TestTaskStatus.QUEUED) }
        taskAddedSignal.trySend(Unit)
        advanceTimeBy(200)
        runCurrent()

        _installQueue.update { it + TestTask("game3", TestTaskStatus.QUEUED) }
        taskAddedSignal.trySend(Unit)
        advanceTimeBy(200)
        runCurrent()

        // Process remaining tasks
        advanceTimeBy(1000)
        runCurrent()

        // All tasks should have been processed
        assertEquals(3, processedTasks.size)
        assertTrue(processedTasks.containsAll(listOf("game1", "game2", "game3")))

        processorJob.cancel()
    }

    // ========== Tests demonstrating the BUG (pre-fix behavior) ==========

    @Suppress("UnusedVariable")
    @Test
    fun `BUGGY - processor exits before task is visible in StateFlow`() = runTest {
        // Start BUGGY processor BEFORE task is visible
        val processorJob = backgroundScope.startQueueProcessorBuggy()

        // Advance minimal time - processor will immediately exit
        advanceTimeBy(10)
        runCurrent()

        // Now "add" the task (too late, processor already exited)
        _installQueue.value = listOf(TestTask("game-v1.0", TestTaskStatus.QUEUED))

        // Even after waiting, task won't be processed because processor exited
        advanceTimeBy(2000)

        // BUG: Task was never processed because processor exited too early
        assertTrue("BUG DEMONSTRATION: Task should NOT have been processed in buggy version",
            processedTasks.isEmpty())
        assertEquals("BUG DEMONSTRATION: Task should still be QUEUED",
            TestTaskStatus.QUEUED, _installQueue.value.first().status)

        // Note: processorJob already cancelled itself (exited immediately)
    }

    // ========== App restart scenario tests ==========

    @Test
    fun `FIXED - processor handles app restart with existing queued tasks`() = runTest {
        // Simulate app restart: queue already has tasks from Room DB
        _installQueue.value = listOf(
            TestTask("game1", TestTaskStatus.QUEUED),
            TestTask("game2", TestTaskStatus.QUEUED)
        )

        // Start processor (simulates resumeActiveDownloadObservations → startQueueProcessor)
        val processorJob = backgroundScope.startQueueProcessorFixed()

        // Process tasks
        advanceTimeBy(1000)
        runCurrent()

        // Both tasks should be processed
        assertEquals(2, processedTasks.size)

        processorJob.cancel()
    }

    @Test
    fun `FIXED - processor handles mixed states on restart`() = runTest {
        // Simulate app restart with mixed states (some paused, some queued)
        _installQueue.value = listOf(
            TestTask("game1", TestTaskStatus.PAUSED),
            TestTask("game2", TestTaskStatus.QUEUED),
            TestTask("game3", TestTaskStatus.DOWNLOADING)
        )

        val processorJob = backgroundScope.startQueueProcessorFixed()
        advanceTimeBy(1000)
        runCurrent()

        // Only QUEUED task should be processed
        assertEquals(1, processedTasks.size)
        assertTrue(processedTasks.contains("game2"))

        processorJob.cancel()
    }

    // ========== Edge case tests ==========

    @Test
    fun `FIXED - signal is CONFLATED - multiple signals merge into one`() = runTest {
        val processorJob = backgroundScope.startQueueProcessorFixed()
        advanceTimeBy(100)

        // Send multiple signals rapidly (simulates rapid queue additions)
        repeat(10) {
            taskAddedSignal.trySend(Unit)
        }

        // Add a single task
        _installQueue.value = listOf(TestTask("game", TestTaskStatus.QUEUED))

        advanceTimeBy(600)
        runCurrent()

        // Processor should handle this gracefully (CONFLATED channel only keeps last signal)
        assertEquals(1, processedTasks.size)

        processorJob.cancel()
    }

    @Test
    fun `FIXED - processor rechecks queue after timeout if tasks appeared`() = runTest {
        val processorJob = backgroundScope.startQueueProcessorFixed()

        // Wait for first timeout cycle
        advanceTimeBy(400)

        // Add task during timeout window (no signal sent - edge case)
        _installQueue.value = listOf(TestTask("game", TestTaskStatus.QUEUED))

        // Processor will timeout and recheck
        advanceTimeBy(200)
        runCurrent()

        // Task should be processed on recheck
        assertTrue("Task should be processed after timeout recheck",
            processedTasks.contains("game"))

        processorJob.cancel()
    }
}
