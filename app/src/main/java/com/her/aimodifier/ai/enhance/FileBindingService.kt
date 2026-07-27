package com.her.aimodifier.ai.enhance

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * 文件绑定/补丁应用服务。
 *
 * 核心能力：
 * 1. 解析 AI 生成的结构化编辑块（`[START-REPLACE]` / `[START-DELETE]`）；
 * 2. 对每个块中的 `[OLD]` 内容，使用 3-gram Jaccard 相似度在原始文件中
 *    进行模糊匹配，找到最佳对应行范围（支持并行滑动窗口）；
 * 3. 按从后向前的顺序应用替换/删除操作，并尽量保留行首缩进等边界上下文；
 * 4. 生成 unified diff 展示变更。
 *
 * 适配说明：
 * - 用 `android.util.Log` 替代 AppLogger
 * - 用内联 LCS 算法替代 java-diff-utils 库，实现简化的 unified diff 生成
 * - 保留核心的模糊匹配和补丁应用逻辑
 *
 * @param context Android Context（保留用于未来扩展，当前未使用）
 */
class FileBindingService(context: Context) {

    companion object {
        private const val TAG = "FileBindingService"
        private val EDIT_BLOCK_REGEX =
            """\[START-(REPLACE|DELETE)\]\s*\n(.*?)\[END-\1\]""".toRegex(
                RegexOption.DOT_MATCHES_ALL
            )
        private const val PARALLEL_MIN_ITERATIONS = 2000

        /** diff 计算的最大行数，超过此值时回退为简单全量替换展示，避免 O(n*m) 内存爆炸 */
        private const val MAX_DIFF_LINES = 5000
    }

    private enum class EditAction {
        REPLACE,
        DELETE
    }

    enum class StructuredEditAction {
        REPLACE,
        DELETE
    }

    data class StructuredEditOperation(
        val action: StructuredEditAction,
        val oldContent: String,
        val newContent: String = ""
    )

    private data class EditOperation(
        val action: EditAction,
        val oldContent: String,
        val newContent: String
    )

    private data class MatchSearchResult(
        val bestScore: Double,
        val startLine: Int,
        val endLine: Int,
        val sizeDiff: Int,
        val lengthDiff: Int,
        val windows: Int,
        val lcsCalculations: Int
    )

    /** diff 操作类型 */
    private enum class DiffOp {
        KEEP,
        DELETE,
        INSERT
    }

    /**
     * 通过应用结构化编辑块来处理文件绑定。
     *
     * 1. 从 AI 生成的代码中解析 `[START-REPLACE]` 或 `[START-DELETE]` 块；
     * 2. 对每个块，使用 `[OLD]` 部分作为搜索模式；
     * 3. 对 `originalContent` 进行模糊匹配，找到要修改的精确行范围（忽略空白和换行）；
     * 4. 找到正确范围后，应用 `REPLACE` 或 `DELETE` 操作；
     * 5. 如果未找到结构化块，默认进行全文件替换。
     *
     * 注意：如果原始文件非空且 AI 代码不含 `[START-` 标记，会拒绝直接覆盖。
     *
     * @param originalContent 文件的原始内容
     * @param aiGeneratedCode AI 生成的代码（含编辑块或完整内容）
     * @param onProgress 可选的进度回调
     * @return Pair(最终合并后的内容, diff 字符串)
     */
    suspend fun processFileBinding(
        originalContent: String,
        aiGeneratedCode: String,
        onProgress: ((Float, String) -> Unit)? = null
    ): Pair<String, String> {
        if (originalContent.isNotEmpty() && !aiGeneratedCode.contains("[START-")) {
            val errorMsg =
                "If you want to rewrite an entire existing file: please delete_file first then use apply_file with type=create (do not overwrite directly)." +
                "If you want to modify a file: please use apply_file with type=replace/delete and provide old/new (or old)."
            Log.w(TAG, "Refusing full overwrite for existing content without structured edit blocks. $errorMsg")
            return Pair(originalContent, errorMsg)
        }

        if (aiGeneratedCode.contains("[START-")) {
            onProgress?.invoke(0f, "Parsing patch...")
            Log.d(TAG, "Structured edit blocks detected. Attempting fuzzy patch.")
            try {
                val (success, resultString) = applyFuzzyPatch(originalContent, aiGeneratedCode, onProgress)
                if (success) {
                    onProgress?.invoke(1f, "Patch applied")
                    Log.d(TAG, "Fuzzy patch succeeded.")
                    val diffString = generateDiff(originalContent.replace("\r\n", "\n"), resultString)
                    return Pair(resultString, diffString)
                } else {
                    Log.w(TAG, "Fuzzy patch application failed. Reasons: $resultString")
                    return Pair(originalContent, "Error: Could not apply patch. Reasons: $resultString")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during fuzzy patch process.", e)
                return Pair(originalContent, "Error: An unexpected exception occurred during the patching process: ${e.message}")
            }
        }

        // Default to full file replacement if no special instructions are found
        Log.d(TAG, "No structured blocks found. Assuming full file replacement.")
        val normalizedOriginalContent = originalContent.replace("\r\n", "\n")
        val normalizedAiGeneratedCode = aiGeneratedCode.replace("\r\n", "\n").trim()
        val diffString = generateDiff(normalizedOriginalContent, normalizedAiGeneratedCode)
        return Pair(normalizedAiGeneratedCode, diffString)
    }

    /**
     * 通过结构化编辑操作列表处理文件绑定。
     *
     * @param originalContent 原始内容
     * @param operations 编辑操作列表
     * @param onProgress 可选的进度回调
     * @return Pair(最终内容, diff 字符串)
     */
    suspend fun processFileBindingOperations(
        originalContent: String,
        operations: List<StructuredEditOperation>,
        onProgress: ((Float, String) -> Unit)? = null
    ): Pair<String, String> {
        if (operations.isEmpty()) {
            return Pair(originalContent, "Error: No valid edit operations provided")
        }

        val internalOps = operations.mapNotNull { op ->
            val old = op.oldContent
            if (old.isBlank()) return@mapNotNull null
            when (op.action) {
                StructuredEditAction.REPLACE -> {
                    val newContent = op.newContent
                    if (newContent.isBlank()) return@mapNotNull null
                    EditOperation(EditAction.REPLACE, old, newContent)
                }
                StructuredEditAction.DELETE -> {
                    EditOperation(EditAction.DELETE, old, "")
                }
            }
        }

        if (internalOps.isEmpty()) {
            return Pair(originalContent, "Error: No valid edit operations provided")
        }

        onProgress?.invoke(0f, "Searching match...")
        val (success, resultString) = applyFuzzyOperations(originalContent, internalOps, onProgress)
        if (success) {
            onProgress?.invoke(1f, "Patch applied")
            val diffString = generateDiff(originalContent.replace("\r\n", "\n"), resultString)
            return Pair(resultString, diffString)
        }
        return Pair(originalContent, "Error: Could not apply patch. Reasons: $resultString")
    }

    private fun generateDiff(original: String, modified: String): String {
        return generateUnifiedDiff(original, modified)
    }

    /**
     * 生成带行号和变更指示（+、-）的 unified diff 字符串。
     *
     * 这是一个公共工具方法，可被其他服务使用。
     * 使用内联的 LCS（最长公共子序列）算法替代 java-diff-utils 库。
     *
     * 对于超大文件（超过 [MAX_DIFF_LINES] 行），回退为简单的统计信息，
     * 避免 O(n*m) 的内存消耗。
     *
     * @param original 原始文本内容
     * @param modified 修改后的文本内容
     * @return 表示 unified diff 的格式化字符串
     */
    fun generateUnifiedDiff(original: String, modified: String): String {
        val originalLines = if (original.isEmpty()) emptyList() else original.lines()
        val modifiedLines = if (modified.isEmpty()) emptyList() else modified.lines()

        // 超大文件回退：仅输出统计信息，避免内存爆炸
        if (originalLines.size > MAX_DIFF_LINES || modifiedLines.size > MAX_DIFF_LINES) {
            val additions = (modifiedLines.size - originalLines.size).coerceAtLeast(0)
            val deletions = (originalLines.size - modifiedLines.size).coerceAtLeast(0)
            return buildString {
                appendLine("Changes: +$additions -$deletions lines (file too large for detailed diff)")
            }
        }

        if (originalLines == modifiedLines) {
            return "No changes detected (files are identical)"
        }

        // 计算 LCS 操作序列
        val ops = computeLcsOps(originalLines, modifiedLines)

        // 统计增删行数
        var additions = 0
        var deletions = 0
        for (op in ops) {
            when (op) {
                DiffOp.INSERT -> additions++
                DiffOp.DELETE -> deletions++
                DiffOp.KEEP -> {}
            }
        }

        val sb = StringBuilder()
        sb.appendLine("Changes: +$additions -$deletions lines")

        // 生成带行号的 unified diff
        val contextLines = 3
        val resultLines = formatDiffWithLineNumbers(ops, originalLines, modifiedLines, contextLines)
        sb.append(resultLines.joinToString("\n"))
        return sb.toString()
    }

    /**
     * 使用动态规划计算两个行序列的 LCS 操作序列。
     *
     * 时间复杂度 O(n*m)，空间复杂度 O(n*m)。
     * 仅适用于中等大小的文件。
     */
    private fun computeLcsOps(original: List<String>, modified: List<String>): List<DiffOp> {
        val n = original.size
        val m = modified.size

        // dp[i][j] = original[0..i-1] 与 modified[0..j-1] 的 LCS 长度
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 1..n) {
            for (j in 1..m) {
                dp[i][j] = if (original[i - 1] == modified[j - 1]) {
                    dp[i - 1][j - 1] + 1
                } else {
                    maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }

        // 回溯
        val ops = mutableListOf<DiffOp>()
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            if (original[i - 1] == modified[j - 1]) {
                ops.add(DiffOp.KEEP)
                i--
                j--
            } else if (dp[i - 1][j] >= dp[i][j - 1]) {
                ops.add(DiffOp.DELETE)
                i--
            } else {
                ops.add(DiffOp.INSERT)
                j--
            }
        }
        while (i > 0) {
            ops.add(DiffOp.DELETE)
            i--
        }
        while (j > 0) {
            ops.add(DiffOp.INSERT)
            j--
        }
        ops.reverse()
        return ops
    }

    /**
     * 将 LCS 操作序列格式化为带行号的 unified diff。
     *
     * 输出格式：
     * ```
     * @@ -origStart,origCount +newStart,newCount @@
     *  origLine|context line
     * -origLine|removed line
     * +newLine|added line
     * ```
     */
    private fun formatDiffWithLineNumbers(
        ops: List<DiffOp>,
        original: List<String>,
        modified: List<String>,
        contextLines: Int
    ): List<String> {
        val result = mutableListOf<String>()

        // 找到所有变更点的索引（非 KEEP 操作）
        val changeIndices = mutableListOf<Int>()
        for (index in ops.indices) {
            if (ops[index] != DiffOp.KEEP) {
                changeIndices.add(index)
            }
        }

        if (changeIndices.isEmpty()) return result

        // 将变更点分组为 hunk（间距超过 2*contextLines 的变更点拆分为不同 hunk）
        val hunks = mutableListOf<MutableList<Int>>()
        var currentHunk = mutableListOf(changeIndices[0])
        for (k in 1 until changeIndices.size) {
            if (changeIndices[k] - changeIndices[k - 1] > 2 * contextLines) {
                hunks.add(currentHunk)
                currentHunk = mutableListOf(changeIndices[k])
            } else {
                currentHunk.add(changeIndices[k])
            }
        }
        hunks.add(currentHunk)

        var origLineNum = 1
        var newLineNum = 1
        var opIndex = 0

        for (hunk in hunks) {
            val hunkStart = (hunk.first() - contextLines).coerceAtLeast(0)
            val hunkEnd = (hunk.last() + contextLines).coerceAtMost(ops.size - 1)

            // 跳过到 hunk 开始前，推进行号
            while (opIndex < hunkStart) {
                when (ops[opIndex]) {
                    DiffOp.KEEP -> { origLineNum++; newLineNum++ }
                    DiffOp.DELETE -> origLineNum++
                    DiffOp.INSERT -> newLineNum++
                }
                opIndex++
            }

            // 计算 hunk 的行范围
            val hunkOrigStart = origLineNum
            val hunkNewStart = newLineNum
            var hunkOrigCount = 0
            var hunkNewCount = 0
            for (k in hunkStart..hunkEnd) {
                when (ops[k]) {
                    DiffOp.KEEP -> { hunkOrigCount++; hunkNewCount++ }
                    DiffOp.DELETE -> hunkOrigCount++
                    DiffOp.INSERT -> hunkNewCount++
                }
            }

            // 输出 hunk header
            result.add(formatHunkHeader(hunkOrigStart, hunkOrigCount, hunkNewStart, hunkNewCount))

            // 输出 hunk 内容
            while (opIndex <= hunkEnd) {
                when (ops[opIndex]) {
                    DiffOp.KEEP -> {
                        val lineIdx = origLineNum - 1
                        val content = original.getOrElse(lineIdx) { "" }
                        result.add(" ${origLineNum.toString().padEnd(4)}|$content")
                        origLineNum++
                        newLineNum++
                    }
                    DiffOp.DELETE -> {
                        val lineIdx = origLineNum - 1
                        val content = original.getOrElse(lineIdx) { "" }
                        result.add("-${origLineNum.toString().padEnd(4)}|$content")
                        origLineNum++
                    }
                    DiffOp.INSERT -> {
                        val lineIdx = newLineNum - 1
                        val content = modified.getOrElse(lineIdx) { "" }
                        result.add("+${newLineNum.toString().padEnd(4)}|$content")
                        newLineNum++
                    }
                }
                opIndex++
            }
        }

        return result
    }

    private fun formatHunkHeader(origStart: Int, origCount: Int, newStart: Int, newCount: Int): String {
        val origPart = if (origCount == 1) "$origStart" else "$origStart,$origCount"
        val newPart = if (newCount == 1) "$newStart" else "$newStart,$newCount"
        return "@@ -$origPart +$newPart @@"
    }

    /**
     * 基于模糊匹配 `[OLD]` 内容块来应用补丁。
     *
     * @return Pair(是否成功, 修改后的内容 或 失败原因)
     */
    private fun applyFuzzyPatch(
        originalContent: String,
        aiPatchCode: String,
        onProgress: ((Float, String) -> Unit)? = null
    ): Pair<Boolean, String> {
        return try {
            val operations = parseEditOperations(aiPatchCode)
            if (operations.isEmpty()) {
                return Pair(false, "No valid edit operations found in the patch code.")
            }
            applyFuzzyOperations(originalContent, operations, onProgress)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply fuzzy patch", e)
            Pair(false, "Failed to apply fuzzy patch due to an exception: ${e.message}")
        }
    }

    private fun applyFuzzyOperations(
        originalContent: String,
        operations: List<EditOperation>,
        onProgress: ((Float, String) -> Unit)? = null
    ): Pair<Boolean, String> {
        onProgress?.invoke(0f, "Searching match...")

        val originalLines = originalContent.lines().toMutableList()
        val enrichedOps = mutableListOf<Triple<EditOperation, Int, Int>>()

        val totalOps = operations.size.coerceAtLeast(1)
        val matchPhaseWeight = 0.8f
        val applyPhaseWeight = 0.2f

        for ((index, op) in operations.withIndex()) {
            val (start, end) = findBestMatchRange(originalLines, op.oldContent) { p, msg ->
                val overall = (matchPhaseWeight * ((index.toFloat() + p) / totalOps.toFloat()))
                    .coerceIn(0f, 0.99f)
                onProgress?.invoke(overall, "Matching ${index + 1}/$totalOps: $msg")
            }
            if (start == -1) {
                Log.w(TAG, "Could not find a suitable match for OLD block: ${op.oldContent.take(100)}...")
                return Pair(false, "Could not find a match for an OLD block. The file may have changed too much.")
            }
            if (hasMultiplePerfectMatches(originalContent, op.oldContent)) {
                Log.w(TAG, "Multiple perfect matches found for OLD block; aborting to avoid ambiguous replacement.")
                return Pair(false, "Found multiple perfect matches for an OLD block in the target file. Please refine the patch so it only matches a single location.")
            }
            enrichedOps.add(Triple(op, start, end))
        }

        // Sort operations by start line in descending order to apply from the bottom up
        enrichedOps.sortByDescending { it.second }

        var applied = 0
        for ((op, start, end) in enrichedOps) {
            Log.d(TAG, "Applying ${op.action} at lines ${start + 1}-${end + 1}")

            val originalSegment = originalLines.subList(start, end + 1).toList()
            val boundaryPreservingLines = tryApplyBoundaryPreservingEdit(originalSegment, op)

            for (i in end downTo start) {
                originalLines.removeAt(i)
            }

            if (boundaryPreservingLines != null) {
                originalLines.addAll(start, boundaryPreservingLines)
            } else if (op.action == EditAction.REPLACE) {
                val newLinesRaw = op.newContent.lines()

                val newLines = if (originalSegment.isNotEmpty() &&
                    start == end &&
                    newLinesRaw.size == 1
                ) {
                    val originalFirstLine = originalSegment.first()
                    val indentPrefix = originalFirstLine.takeWhile { it == ' ' || it == '\t' }
                    val newLine = newLinesRaw.first()

                    if (indentPrefix.isNotEmpty() &&
                        !newLine.startsWith(" ") &&
                        !newLine.startsWith("\t")
                    ) {
                        listOf(indentPrefix + newLine)
                    } else {
                        newLinesRaw
                    }
                } else {
                    newLinesRaw
                }

                originalLines.addAll(start, newLines)
            }

            applied++
            val overall = (matchPhaseWeight + (applyPhaseWeight * (applied.toFloat() / totalOps.toFloat())))
                .coerceIn(0f, 0.99f)
            onProgress?.invoke(overall, "Applying $applied/$totalOps")
        }

        return Pair(true, originalLines.joinToString("\n"))
    }

    private fun tryApplyBoundaryPreservingEdit(
        originalSegment: List<String>,
        op: EditOperation
    ): List<String>? {
        if (originalSegment.isEmpty()) return null

        val oldLines = op.oldContent.lines()
        if (oldLines.isEmpty()) return null

        val startIndex = findUniqueOccurrence(originalSegment.first(), oldLines.first()) ?: return null
        val endIndex = findUniqueOccurrence(originalSegment.last(), oldLines.last()) ?: return null

        val prefix = originalSegment.first().substring(0, startIndex)
        val suffix = originalSegment.last().substring(endIndex + oldLines.last().length)

        return when (op.action) {
            EditAction.REPLACE -> {
                val newLines = op.newContent.lines()
                if (newLines.isEmpty()) return null
                when (newLines.size) {
                    1 -> listOf(prefix + newLines.first() + suffix)
                    else -> buildList {
                        add(prefix + newLines.first())
                        addAll(newLines.subList(1, newLines.lastIndex))
                        add(newLines.last() + suffix)
                    }
                }
            }
            EditAction.DELETE -> {
                val updatedLine = prefix + suffix
                if (updatedLine.isEmpty()) emptyList() else listOf(updatedLine)
            }
        }
    }

    private fun findUniqueOccurrence(line: String, fragment: String): Int? {
        if (fragment.isEmpty()) return null

        val firstIndex = line.indexOf(fragment)
        if (firstIndex == -1) return null

        val duplicateMatchIndex = line.indexOf(fragment, firstIndex + fragment.length)
        if (duplicateMatchIndex != -1) return null

        return firstIndex
    }

    private fun parseEditOperations(patchCode: String): List<EditOperation> {
        val operations = mutableListOf<EditOperation>()
        val lines = patchCode.lines()
        var i = 0

        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("[START-")) {
                val header = line
                val actionStr = header.substringAfter("[START-").substringBefore("]")

                val action = try {
                    EditAction.valueOf(actionStr)
                } catch (e: IllegalArgumentException) {
                    i++
                    continue // Skip invalid action
                }

                var oldContent = ""
                var newContent = ""
                var inBlock: String? = null
                i++ // Move to content

                while (i < lines.size && !lines[i].trim().startsWith("[END-$actionStr]")) {
                    val currentLine = lines[i]
                    val trimmedLine = currentLine.trim()

                    if (trimmedLine.startsWith("[OLD]")) {
                        inBlock = "OLD"
                        val inline = currentLine.substringAfter("[OLD]", "")
                        if (inline.isNotEmpty()) {
                            oldContent += inline + "\n"
                        }
                    } else if (trimmedLine.startsWith("[NEW]")) {
                        inBlock = "NEW"
                        val inline = currentLine.substringAfter("[NEW]", "")
                        if (inline.isNotEmpty()) {
                            newContent += inline + "\n"
                        }
                    } else if (trimmedLine.startsWith("[/OLD]")) inBlock = null
                    else if (trimmedLine.startsWith("[/NEW]")) inBlock = null
                    else {
                        when (inBlock) {
                            "OLD" -> oldContent += currentLine + "\n"
                            "NEW" -> newContent += currentLine + "\n"
                        }
                    }
                    i++
                }

                val normalizedOld = oldContent.removeSuffix("\n").removeSuffix("\r")
                val normalizedNew = newContent.removeSuffix("\n").removeSuffix("\r")

                // Basic validation
                if ((action == EditAction.REPLACE || action == EditAction.DELETE) && normalizedOld.isBlank()) {
                    i++
                    continue // Skip invalid operation
                }
                if (action == EditAction.REPLACE && normalizedNew.isBlank()) {
                    i++
                    continue // Skip invalid operation
                }

                operations.add(EditOperation(action, normalizedOld, normalizedNew))
            }
            i++
        }
        return operations
    }

    /**
     * 在原始行列表中查找与 `oldContent` 最佳匹配的行范围。
     *
     * 使用 3-gram Jaccard 相似度进行模糊匹配，支持并行滑动窗口搜索，
     * 找到 100% 匹配时提前终止。窗口大小在目标行数 ±20% 范围内浮动。
     *
     * @return Pair(起始行号, 结束行号)，未找到足够好的匹配时返回 (-1, -1)
     */
    private fun findBestMatchRange(
        originalLines: List<String>,
        oldContent: String,
        onProgress: ((Float, String) -> Unit)? = null
    ): Pair<Int, Int> {
        val oldContentLines = oldContent.lines()
        val numOldLines = oldContentLines.size
        if (numOldLines == 0) return -1 to -1
        if (originalLines.isEmpty()) return -1 to -1

        Log.d(TAG, "开始查找最佳匹配范围，原始文件行数: ${originalLines.size}, 目标块行数: $numOldLines")
        val startTime = System.currentTimeMillis()
        var totalWindows = 0
        var lcsCalculations = 0

        // --- 优化1：预计算与规范化 ---
        Log.d(TAG, "开始预计算与规范化...")
        val normalizedOldContent = oldContent.replace(Regex("\\s+"), "")
        val normalizedOldLength = normalizedOldContent.length
        val baseNgrams = buildNgrams(normalizedOldContent)
        if (baseNgrams.isEmpty()) {
            Log.w(TAG, "OLD 块在去空白后过短，无法构建 n-gram，放弃匹配。")
            return -1 to -1
        }

        val lineStartIndices = mutableListOf<Int>()
        val normalizedOriginalContent = buildString {
            originalLines.forEachIndexed { index, line ->
                if (index % 1000 == 0 && index > 0) {
                    Log.d(TAG, "正在预处理行: $index/${originalLines.size}")
                }
                lineStartIndices.add(length)
                append(line.replace(Regex("\\s+"), ""))
            }
            lineStartIndices.add(length) // 添加一个末尾索引，方便计算最后一行
        }
        Log.d(TAG, "预计算完成，规范化后字符数: ${normalizedOriginalContent.length}")

        // --- 阶段一：计算目标窗口尺寸范围 ---
        val delta = (numOldLines * 0.2).toInt() + 2 // 扩大到20%的容错范围，并确保至少有2行的浮动
        val targetSizes = (maxOf(1, numOldLines - delta))..(numOldLines + delta)

        var bestMatchScore = 0.0
        var bestMatchRange = -1 to -1
        var bestMatchSizeDiff = Int.MAX_VALUE
        var bestMatchLengthDiff = Int.MAX_VALUE

        // --- 阶段二：并行滑动窗口搜索 ---
        val totalIterations = originalLines.size.toLong() * targetSizes.count().toLong()
        Log.d(TAG, "开始滑动窗口匹配（并行），总迭代次数: $totalIterations")

        val processedWindows = AtomicLong(0L)
        val lastProgressEmitMs = AtomicLong(0L)

        fun maybeEmitProgress(processed: Long) {
            if (onProgress == null) return
            val total = totalIterations.coerceAtLeast(1L)
            val p = (processed.toDouble() / total.toDouble()).coerceIn(0.0, 0.99)
            val now = System.currentTimeMillis()
            val last = lastProgressEmitMs.get()
            if (now - last < 200L) return
            if (!lastProgressEmitMs.compareAndSet(last, now)) return
            onProgress.invoke(p.toFloat(), "Searching... ${(p * 100).toInt()}%")
        }

        val availableCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        val threadCount = minOf(availableCores, originalLines.size)
        val segmentSize = (originalLines.size + threadCount - 1) / threadCount
        val foundPerfectMatch = AtomicBoolean(false)

        val executor = Executors.newFixedThreadPool(threadCount)
        try {
            val tasks = mutableListOf<Future<MatchSearchResult>>()

            for (threadIndex in 0 until threadCount) {
                val startLine = threadIndex * segmentSize
                val endExclusive = minOf(originalLines.size, startLine + segmentSize)
                if (startLine >= endExclusive) continue

                val task = java.util.concurrent.Callable {
                    var localBestScore = 0.0
                    var localBestStart = -1
                    var localBestEnd = -1
                    var localBestSizeDiff = Int.MAX_VALUE
                    var localBestLengthDiff = Int.MAX_VALUE
                    var localWindows = 0
                    var localLcs = 0
                    var localChunk = 0L

                    for (i in startLine until endExclusive) {
                        if (foundPerfectMatch.get()) {
                            break
                        }

                        for (size in targetSizes) {
                            if (foundPerfectMatch.get()) {
                                break
                            }

                            val endLine = i + size
                            if (endLine > originalLines.size) {
                                break
                            }

                            localWindows++
                            localChunk++
                            if (localChunk >= 2048L) {
                                val processed = processedWindows.addAndGet(localChunk)
                                localChunk = 0L
                                maybeEmitProgress(processed)
                            }

                            val startCharIndex = lineStartIndices[i]
                            val endCharIndex = lineStartIndices[endLine]
                            val normalizedWindow =
                                normalizedOriginalContent.substring(startCharIndex, endCharIndex)

                            val sizeDiff = abs(size - numOldLines)
                            val lengthDiff = abs(normalizedWindow.length - normalizedOldLength)

                            localLcs++
                            val score = ngramSimilarity(baseNgrams, normalizedWindow)

                            val isBetter =
                                (score > localBestScore) ||
                                    (score == localBestScore &&
                                        (sizeDiff < localBestSizeDiff ||
                                            (sizeDiff == localBestSizeDiff &&
                                                (lengthDiff < localBestLengthDiff ||
                                                    (lengthDiff == localBestLengthDiff &&
                                                        (localBestStart == -1 || i < localBestStart))))))

                            if (isBetter) {
                                localBestScore = score
                                localBestStart = i
                                localBestEnd = endLine - 1
                                localBestSizeDiff = sizeDiff
                                localBestLengthDiff = lengthDiff
                                val matchPercentage = (localBestScore * 100).toInt()
                                Log.d(
                                    TAG,
                                    "并行块[$threadIndex] 发现更佳匹配: 行 ${i + 1}-$endLine, 相似度: $matchPercentage%"
                                )

                                if (localBestScore == 1.0 && localBestSizeDiff == 0 && localBestLengthDiff == 0) {
                                    foundPerfectMatch.set(true)
                                    Log.d(TAG, "并行块[$threadIndex] 已找到100%匹配，提前结束该块搜索。")
                                    return@Callable MatchSearchResult(
                                        localBestScore,
                                        localBestStart,
                                        localBestEnd,
                                        localBestSizeDiff,
                                        localBestLengthDiff,
                                        localWindows,
                                        localLcs
                                    )
                                }
                            }
                        }
                    }

                    if (localChunk > 0L) {
                        val processed = processedWindows.addAndGet(localChunk)
                        maybeEmitProgress(processed)
                    }

                    MatchSearchResult(
                        localBestScore,
                        localBestStart,
                        localBestEnd,
                        localBestSizeDiff,
                        localBestLengthDiff,
                        localWindows,
                        localLcs
                    )
                }

                tasks.add(executor.submit(task))
            }

            for (future in tasks) {
                try {
                    val result = future.get()
                    totalWindows += result.windows
                    lcsCalculations += result.lcsCalculations

                    val isBetter =
                        (result.bestScore > bestMatchScore) ||
                            (result.bestScore == bestMatchScore &&
                                (result.sizeDiff < bestMatchSizeDiff ||
                                    (result.sizeDiff == bestMatchSizeDiff &&
                                        (result.lengthDiff < bestMatchLengthDiff ||
                                            (result.lengthDiff == bestMatchLengthDiff &&
                                                (bestMatchRange.first == -1 || result.startLine < bestMatchRange.first))))))

                    if (isBetter) {
                        bestMatchScore = result.bestScore
                        bestMatchRange = result.startLine to result.endLine
                        bestMatchSizeDiff = result.sizeDiff
                        bestMatchLengthDiff = result.lengthDiff
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting file binding search result", e)
                }
            }
        } finally {
            executor.shutdown()
        }

        // 记录最终结果
        val totalTime = (System.currentTimeMillis() - startTime) / 1000.0
        val result = if (bestMatchScore > 0.9) {
            val (start, end) = bestMatchRange
            Log.d(
                TAG,
                "匹配完成! 最佳匹配: 行 ${start + 1}-${end + 1}, 相似度: ${(bestMatchScore * 100).toInt()}%, " +
                        "总耗时: ${String.format("%.2f", totalTime)}s, " +
                        "总窗口数: $totalWindows, 总LCS计算: $lcsCalculations"
            )
            bestMatchRange
        } else {
            Log.w(TAG, "未找到足够好的匹配 (最高相似度: ${(bestMatchScore * 100).toInt()}% < 90%)")
            -1 to -1
        }

        onProgress?.invoke(1f, "Search done")

        return result
    }

    /** 构建 n-gram 集合（默认 n=3）。 */
    private fun buildNgrams(s: String, n: Int = 3): Set<String> {
        if (s.length < n) return emptySet()
        return s.windowed(n, 1).toSet()
    }

    /** 计算 3-gram Jaccard 相似度。 */
    private fun ngramSimilarity(baseNgrams: Set<String>, s2: String, n: Int = 3): Double {
        if (baseNgrams.isEmpty() || s2.isEmpty()) return 0.0
        if (s2.length < n) return 0.0

        val ngrams2 = s2.windowed(n, 1).toSet()
        if (ngrams2.isEmpty()) return 0.0

        val intersection = baseNgrams.intersect(ngrams2).size
        val union = baseNgrams.size + ngrams2.size - intersection

        return if (union == 0) 0.0 else intersection.toDouble() / union.toDouble()
    }

    /** 检查原始内容中是否存在多个对 oldContent 的完美匹配（用于消歧）。 */
    private fun hasMultiplePerfectMatches(originalContent: String, oldContent: String): Boolean {
        val normalizedOld = oldContent.replace(Regex("\\s+"), "")
        if (normalizedOld.isEmpty()) return false

        val normalizedOriginal = originalContent.replace(Regex("\\s+"), "")
        var count = 0
        var index = normalizedOriginal.indexOf(normalizedOld)
        while (index >= 0) {
            count++
            if (count > 1) {
                return true
            }
            index = normalizedOriginal.indexOf(normalizedOld, index + normalizedOld.length)
        }
        return false
    }
}
