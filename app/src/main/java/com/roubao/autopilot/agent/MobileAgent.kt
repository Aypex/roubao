package com.roubao.autopilot.agent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import com.roubao.autopilot.App
import com.roubao.autopilot.controller.AppScanner
import com.roubao.autopilot.controller.DeviceController
import com.roubao.autopilot.data.ExecutionStep
import com.roubao.autopilot.skills.SkillManager
import com.roubao.autopilot.ui.OverlayService
import com.roubao.autopilot.vlm.VLMClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * Mobile Agent 主循环 - 移植自 MobileAgent-v3
 *
 * 新增 Skill 层支持：
 * - 快速路径：高置信度 delegation Skill 直接执行
 * - 增强模式：GUI 自动化 Skill 提供上下文指导
 */
class MobileAgent(
    private val vlmClient: VLMClient,
    private val controller: DeviceController,
    private val context: Context
) {
    // App 扫描器 (使用 App 单例中的实例)
    private val appScanner: AppScanner = App.getInstance().appScanner
    private val manager = Manager()
    private val executor = Executor()
    private val reflector = ActionReflector()
    private val notetaker = Notetaker()

    // Skill 管理器
    private val skillManager: SkillManager? = try {
        SkillManager.getInstance().also {
            println("[Baozi] SkillManager loaded, total ${it.getAllSkills().size} Skills")
            // Set VLM client for intent matching
            it.setVLMClient(vlmClient)
        }
    } catch (e: Exception) {
        println("[Baozi] SkillManager load failed: ${e.message}")
        null
    }

    // 状态流
    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    /**
     * 执行指令
     */
    suspend fun runInstruction(
        instruction: String,
        maxSteps: Int = 25,
        useNotetaker: Boolean = false
    ): AgentResult {
        log("Starting execution: $instruction")

        // Use LLM to match Skill, generate context for Agent (no action taken)
        log("Analyzing intent...")
        val skillContext = skillManager?.generateAgentContextWithLLM(instruction)

        val infoPool = InfoPool(instruction = instruction)

        // 初始化 Executor 的对话记忆
        val executorSystemPrompt = buildString {
            append("You are an agent who can operate an Android phone. ")
            append("Decide the next action based on the current state.\n\n")
            append("User Request: $instruction\n")
        }
        infoPool.executorMemory = ConversationMemory.withSystemPrompt(executorSystemPrompt)
        log("Initialized conversation memory")

        // If there's Skill context, add to InfoPool so Manager knows available tools
        if (!skillContext.isNullOrEmpty() && skillContext != "No matching skill or available app found. Please use general GUI automation to complete the task.") {
            infoPool.skillContext = skillContext
            log("Matched available skill:\n$skillContext")
        } else {
            log("No specific skill matched, using general GUI automation")
        }

        // Get screen size
        val (width, height) = controller.getScreenSize()
        infoPool.screenWidth = width
        infoPool.screenHeight = height
        log("Screen size: ${width}x${height}")

        // Get installed apps list (non-system only, limited count to avoid long prompts)
        val apps = appScanner.getApps()
            .filter { !it.isSystem }
            .take(50)
            .map { it.appName }
        infoPool.installedApps = apps.joinToString(", ")
        log("Loaded ${apps.size} apps")

        // Show overlay (with stop button)
        OverlayService.show(context, "Starting...") {
            // Stop callback - set state to stopped
            // Note: coroutine cancellation handled in MainActivity
            updateState { copy(isRunning = false) }
            // Call stop() to ensure cleanup
            stop()
        }

        updateState { copy(isRunning = true, currentStep = 0, instruction = instruction) }

        try {
            for (step in 0 until maxSteps) {
                // 检查协程是否被取消
                coroutineContext.ensureActive()

                // Check if stopped by user
                if (!_state.value.isRunning) {
                    log("User stopped execution")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "User stopped")
                }

                updateState { copy(currentStep = step + 1) }
                log("\n========== Step ${step + 1} ==========")
                OverlayService.update("Step ${step + 1}/$maxSteps")

                // 1. Screenshot (hide overlay first to avoid detection)
                log("Taking screenshot...")
                OverlayService.setVisible(false)
                delay(100) // Wait for overlay to hide
                val screenshotResult = controller.screenshotWithFallback()
                OverlayService.setVisible(true)
                val screenshot = screenshotResult.bitmap

                // Handle sensitive pages (screenshot blocked by system)
                if (screenshotResult.isSensitive) {
                    log("⚠️ Detected sensitive page (screenshot blocked), requesting manual takeover")
                    val confirmed = withContext(Dispatchers.Main) {
                        waitForUserConfirm("Detected sensitive page. Continue execution?")
                    }
                    if (!confirmed) {
                        log("User cancelled, task terminated")
                        OverlayService.hide(context)
                        bringAppToFront()
                        return AgentResult(success = false, message = "Sensitive page, user cancelled")
                    }
                    log("User confirmed to continue (using black placeholder)")
                } else if (screenshotResult.isFallback) {
                    log("⚠️ Screenshot failed, using black placeholder to continue")
                }

                // Check stop status again (after screenshot)
                if (!_state.value.isRunning) {
                    log("User stopped execution")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "User stopped")
                }

                // 2. 检查错误升级
                checkErrorEscalation(infoPool)

                // 3. 跳过 Manager 的情况
                val skipManager = !infoPool.errorFlagPlan &&
                        infoPool.actionHistory.isNotEmpty() &&
                        infoPool.actionHistory.last().type == "invalid"

                // 4. Manager planning
                if (!skipManager) {
                    log("Manager planning...")

                    // Check stop status
                    if (!_state.value.isRunning) {
                        log("User stopped execution")
                        OverlayService.hide(context)
                        bringAppToFront()
                        return AgentResult(success = false, message = "User stopped")
                    }

                    val planPrompt = manager.getPrompt(infoPool)
                    val planResponse = vlmClient.predict(planPrompt, listOf(screenshot))

                    // Check stop status after VLM call
                    if (!_state.value.isRunning) {
                        log("User stopped execution")
                        OverlayService.hide(context)
                        bringAppToFront()
                        return AgentResult(success = false, message = "User stopped")
                    }

                    if (planResponse.isFailure) {
                        log("Manager call failed: ${planResponse.exceptionOrNull()?.message}")
                        continue
                    }

                    val planResult = manager.parseResponse(planResponse.getOrThrow())
                    infoPool.completedPlan = planResult.completedSubgoal
                    infoPool.plan = planResult.plan

                    log("Plan: ${planResult.plan.take(100)}...")

                    // Check for sensitive page
                    if (planResult.plan.contains("STOP_SENSITIVE")) {
                        log("Detected sensitive page (payment/password), stopped execution")
                        OverlayService.update("Sensitive page, stopped")
                        delay(2000)
                        OverlayService.hide(context)
                        updateState { copy(isRunning = false, isCompleted = false) }
                        bringAppToFront()
                        return AgentResult(success = false, message = "Detected sensitive page (payment/password), safely stopped")
                    }

                    // Check if completed - comprehensive detection
                    // Matches: "Finished", "finished", "Finished!", "Finished: app is open", etc.
                    val planTrimmed = planResult.plan.trim()
                    val planLower = planTrimmed.lowercase()
                    val isFinished = planLower == "finished" ||
                            planTrimmed == "Finished" ||
                            planLower.matches(Regex("^finished[.!:,\\s-].*$"))

                    if (isFinished) {
                        log("Task completed!")
                        OverlayService.update("Complete!")
                        delay(1500)
                        OverlayService.hide(context)
                        updateState { copy(isRunning = false, isCompleted = true) }
                        bringAppToFront()
                        return AgentResult(success = true, message = "Task completed")
                    }
                }

                // 5. Executor decides action (using context memory)
                log("Executor deciding...")

                // Check stop status
                if (!_state.value.isRunning) {
                    log("User stopped execution")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "User stopped")
                }

                val actionPrompt = executor.getPrompt(infoPool)

                // Call VLM with context memory
                val memory = infoPool.executorMemory
                val actionResponse = if (memory != null) {
                    // Add user message (with screenshot)
                    memory.addUserMessage(actionPrompt, screenshot)
                    log("Memory messages: ${memory.size()}, estimated tokens: ${memory.estimateTokens()}")

                    // Call VLM
                    val response = vlmClient.predictWithContext(memory.toMessagesJson())

                    // Remove image to save tokens
                    memory.stripLastUserImage()

                    response
                } else {
                    // Fallback: use regular method
                    vlmClient.predict(actionPrompt, listOf(screenshot))
                }

                // Check stop status after VLM call
                if (!_state.value.isRunning) {
                    log("User stopped execution")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "User stopped")
                }

                if (actionResponse.isFailure) {
                    log("Executor call failed: ${actionResponse.exceptionOrNull()?.message}")
                    continue
                }

                val responseText = actionResponse.getOrThrow()
                val executorResult = executor.parseResponse(responseText)

                // Add assistant response to memory
                memory?.addAssistantMessage(responseText)
                val action = executorResult.action

                log("Thought: ${executorResult.thought.take(80)}...")
                log("Action: ${executorResult.actionStr}")
                log("Description: ${executorResult.description}")

                infoPool.lastActionThought = executorResult.thought
                infoPool.lastSummary = executorResult.description

                if (action == null) {
                    log("Action parsing failed")
                    infoPool.actionHistory.add(Action(type = "invalid"))
                    infoPool.summaryHistory.add(executorResult.description)
                    infoPool.actionOutcomes.add("C")
                    infoPool.errorDescriptions.add("Invalid action format")
                    continue
                }

                // Special handling: answer action
                if (action.type == "answer") {
                    log("Answer: ${action.text}")
                    OverlayService.update("${action.text?.take(20)}...")
                    delay(1500)
                    OverlayService.hide(context)
                    updateState { copy(isRunning = false, isCompleted = true, answer = action.text) }
                    bringAppToFront()
                    return AgentResult(success = true, message = "Answer: ${action.text}")
                }

                // 6. Sensitive operation confirmation
                if (action.needConfirm || action.message != null && action.type in listOf("click", "double_tap", "long_press")) {
                    val confirmMessage = action.message ?: "Confirm this operation?"
                    log("⚠️ Sensitive operation: $confirmMessage")

                    val confirmed = withContext(Dispatchers.Main) {
                        waitForUserConfirm(confirmMessage)
                    }

                    if (!confirmed) {
                        log("❌ User cancelled operation")
                        infoPool.actionHistory.add(action)
                        infoPool.summaryHistory.add("User cancelled: ${executorResult.description}")
                        infoPool.actionOutcomes.add("C")
                        infoPool.errorDescriptions.add("User cancelled")
                        continue
                    }
                    log("✅ User confirmed, continuing execution")
                }

                // 7. Execute action
                log("Executing action: ${action.type}")
                OverlayService.update("${action.type}: ${executorResult.description.take(15)}...")
                executeAction(action, infoPool)
                infoPool.lastAction = action

                // Record execution step immediately (outcome "?" means in progress)
                val currentStepIndex = _state.value.executionSteps.size
                val executionStep = ExecutionStep(
                    stepNumber = step + 1,
                    timestamp = System.currentTimeMillis(),
                    action = action.type,
                    description = executorResult.description,
                    thought = executorResult.thought,
                    outcome = "?" // In progress
                )
                updateState { copy(executionSteps = executionSteps + executionStep) }

                // Wait for action to take effect
                delay(if (step == 0) 5000 else 2000)

                // Check stop status
                if (!_state.value.isRunning) {
                    log("User stopped execution")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "User stopped")
                }

                // 8. Screenshot (after action, hide overlay)
                OverlayService.setVisible(false)
                delay(100)
                val afterScreenshotResult = controller.screenshotWithFallback()
                OverlayService.setVisible(true)
                val afterScreenshot = afterScreenshotResult.bitmap
                if (afterScreenshotResult.isFallback) {
                    log("Post-action screenshot failed, using black placeholder")
                }

                // 9. Reflector analysis
                log("Reflector analyzing...")

                // Check stop status
                if (!_state.value.isRunning) {
                    log("User stopped execution")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "User stopped")
                }

                val reflectPrompt = reflector.getPrompt(infoPool)
                val reflectResponse = vlmClient.predict(reflectPrompt, listOf(screenshot, afterScreenshot))

                val reflectResult = if (reflectResponse.isSuccess) {
                    reflector.parseResponse(reflectResponse.getOrThrow())
                } else {
                    ReflectorResult("C", "Failed to call reflector")
                }

                log("Result: ${reflectResult.outcome} - ${reflectResult.errorDescription.take(50)}")

                // 更新历史
                infoPool.actionHistory.add(action)
                infoPool.summaryHistory.add(executorResult.description)
                infoPool.actionOutcomes.add(reflectResult.outcome)
                infoPool.errorDescriptions.add(reflectResult.errorDescription)
                infoPool.progressStatus = infoPool.completedPlan

                // 更新执行步骤的 outcome（之前添加的步骤 outcome 是 "?"）
                updateState {
                    val updatedSteps = executionSteps.toMutableList()
                    if (currentStepIndex < updatedSteps.size) {
                        updatedSteps[currentStepIndex] = updatedSteps[currentStepIndex].copy(
                            outcome = reflectResult.outcome
                        )
                    }
                    copy(executionSteps = updatedSteps)
                }

                // 10. Notetaker (optional)
                if (useNotetaker && reflectResult.outcome == "A" && action.type != "answer") {
                    log("Notetaker recording...")

                    // Check stop status
                    if (!_state.value.isRunning) {
                        log("User stopped execution")
                        OverlayService.hide(context)
                        bringAppToFront()
                        return AgentResult(success = false, message = "User stopped")
                    }

                    val notePrompt = notetaker.getPrompt(infoPool)
                    val noteResponse = vlmClient.predict(notePrompt, listOf(afterScreenshot))
                    if (noteResponse.isSuccess) {
                        infoPool.importantNotes = notetaker.parseResponse(noteResponse.getOrThrow())
                    }
                }
            }
        } catch (e: CancellationException) {
            log("Task cancelled")
            OverlayService.hide(context)
            updateState { copy(isRunning = false) }
            bringAppToFront()
            throw e
        }

        log("Reached maximum step limit")
        OverlayService.update("Max steps reached")
        delay(1500)
        OverlayService.hide(context)
        updateState { copy(isRunning = false, isCompleted = false) }
        bringAppToFront()
        return AgentResult(success = false, message = "Reached maximum step limit")
    }

    /**
     * 执行具体动作 (在 IO 线程执行，避免 ANR)
     */
    private suspend fun executeAction(action: Action, infoPool: InfoPool) = withContext(Dispatchers.IO) {
        // 动态获取屏幕尺寸（处理横竖屏切换）
        val (screenWidth, screenHeight) = controller.getScreenSize()

        when (action.type) {
            "click" -> {
                val x = mapCoordinate(action.x ?: 0, screenWidth)
                val y = mapCoordinate(action.y ?: 0, screenHeight)
                controller.tap(x, y)
            }
            "double_tap" -> {
                val x = mapCoordinate(action.x ?: 0, screenWidth)
                val y = mapCoordinate(action.y ?: 0, screenHeight)
                controller.doubleTap(x, y)
            }
            "long_press" -> {
                val x = mapCoordinate(action.x ?: 0, screenWidth)
                val y = mapCoordinate(action.y ?: 0, screenHeight)
                controller.longPress(x, y)
            }
            "swipe" -> {
                val x1 = mapCoordinate(action.x ?: 0, screenWidth)
                val y1 = mapCoordinate(action.y ?: 0, screenHeight)
                val x2 = mapCoordinate(action.x2 ?: 0, screenWidth)
                val y2 = mapCoordinate(action.y2 ?: 0, screenHeight)
                controller.swipe(x1, y1, x2, y2)
            }
            "type" -> {
                action.text?.let { controller.type(it) }
            }
            "system_button" -> {
                when (action.button) {
                    "Back", "back" -> controller.back()
                    "Home", "home" -> controller.home()
                    "Enter", "enter" -> controller.enter()
                    else -> log("Unknown system button: ${action.button}")
                }
            }
            "open_app" -> {
                action.text?.let { appName ->
                    // Smart package name matching (client-side fuzzy search, saves tokens)
                    val packageName = appScanner.findPackage(appName)
                    if (packageName != null) {
                        log("Found app: $appName -> $packageName")
                        controller.openApp(packageName)
                    } else {
                        log("App not found: $appName, trying direct open")
                        controller.openApp(appName)
                    }
                }
            }
            "wait" -> {
                // Smart wait: model decides wait duration
                val duration = (action.duration ?: 3).coerceIn(1, 10)
                log("Waiting ${duration} seconds...")
                delay(duration * 1000L)
            }
            "take_over" -> {
                // Human-AI collaboration: pause for user to complete manual operation
                val message = action.message ?: "Please complete the operation and tap continue"
                log("🖐 Human takeover: $message")
                withContext(Dispatchers.Main) {
                    waitForUserTakeOver(message)
                }
                log("✅ User completed, continuing execution")
            }
            else -> {
                log("Unknown action type: ${action.type}")
            }
        }
    }

    /**
     * 等待用户完成手动操作（人机协作）
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun waitForUserTakeOver(message: String) = suspendCancellableCoroutine<Unit> { continuation ->
        com.roubao.autopilot.ui.OverlayService.showTakeOver(message) {
            if (continuation.isActive) {
                continuation.resume(Unit) {}
            }
        }
    }

    /**
     * 等待用户确认敏感操作
     * @return true = 用户确认，false = 用户取消
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun waitForUserConfirm(message: String) = suspendCancellableCoroutine<Boolean> { continuation ->
        com.roubao.autopilot.ui.OverlayService.showConfirm(message) { confirmed ->
            if (continuation.isActive) {
                continuation.resume(confirmed) {}
            }
        }
    }

    /**
     * 坐标映射 - 支持相对坐标和绝对坐标
     *
     * 坐标格式判断:
     * - 0-999: Qwen-VL 相对坐标 (0-999 映射到屏幕)
     * - >= 1000: 绝对像素坐标，直接使用
     *
     * @param value 模型输出的坐标值
     * @param screenMax 屏幕实际尺寸
     */
    private fun mapCoordinate(value: Int, screenMax: Int): Int {
        return if (value < 1000) {
            // 相对坐标 (0-999) -> 绝对像素
            (value * screenMax / 999)
        } else {
            // 绝对坐标，限制在屏幕范围内
            value.coerceAtMost(screenMax)
        }
    }

    /**
     * 检查错误升级
     */
    private fun checkErrorEscalation(infoPool: InfoPool) {
        infoPool.errorFlagPlan = false
        val thresh = infoPool.errToManagerThresh

        if (infoPool.actionOutcomes.size >= thresh) {
            val recentOutcomes = infoPool.actionOutcomes.takeLast(thresh)
            val failCount = recentOutcomes.count { it in listOf("B", "C") }
            if (failCount == thresh) {
                infoPool.errorFlagPlan = true
            }
        }
    }

    // 停止回调（由 MainActivity 设置，用于取消协程）
    var onStopRequested: (() -> Unit)? = null

    /**
     * 停止执行
     */
    fun stop() {
        OverlayService.hide(context)
        updateState { copy(isRunning = false) }
        // 通知 MainActivity 取消协程
        onStopRequested?.invoke()
    }

    /**
     * 清空日志
     */
    fun clearLogs() {
        _logs.value = emptyList()
        updateState { copy(executionSteps = emptyList()) }
    }

    /**
     * 返回肉包App
     */
    private fun bringAppToFront() {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            context.startActivity(intent)
        } catch (e: Exception) {
            log("Failed to return to app: ${e.message}")
        }
    }

    private fun log(message: String) {
        println("[Baozi] $message")
        _logs.value = _logs.value + message
    }

    private fun updateState(update: AgentState.() -> AgentState) {
        _state.value = _state.value.update()
    }

}

data class AgentState(
    val isRunning: Boolean = false,
    val isCompleted: Boolean = false,
    val currentStep: Int = 0,
    val instruction: String = "",
    val answer: String? = null,
    val executionSteps: List<ExecutionStep> = emptyList()
)

data class AgentResult(
    val success: Boolean,
    val message: String
)
