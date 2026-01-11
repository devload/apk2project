package com.whatap.apk2project.deobfuscator.graph

import com.whatap.apk2project.deobfuscator.models.MethodSignals

/**
 * Scores leaf nodes to determine analysis priority
 * Higher score = more valuable to analyze first
 */
class LeafScorer {

    companion object {
        // Methods that should be skipped
        private val SKIP_METHOD_NAMES = setOf(
            "toString", "hashCode", "equals", "clone", "finalize",
            "compareTo", "compare", "iterator", "hasNext", "next",
            "getClass", "notify", "notifyAll", "wait"
        )

        // Android lifecycle methods - high priority
        private val LIFECYCLE_METHODS = setOf(
            "onCreate", "onStart", "onResume", "onPause", "onStop", "onDestroy",
            "onCreateView", "onViewCreated", "onActivityCreated",
            "onAttach", "onDetach", "onDestroyView",
            "onBind", "onUnbind", "onRebind",
            "onReceive", "onStartCommand",
            "onCreateOptionsMenu", "onOptionsItemSelected",
            "onClick", "onLongClick", "onTouch",
            "onItemClick", "onItemSelected",
            "onTextChanged", "afterTextChanged", "beforeTextChanged"
        )

        // External API patterns that indicate meaningful functionality
        private val EXTERNAL_API_PATTERNS = listOf(
            Regex(".*\\.execute\\(.*"),
            Regex(".*\\.enqueue\\(.*"),
            Regex(".*\\.call\\(.*"),
            Regex(".*Request.*"),
            Regex(".*Response.*"),
            Regex(".*Http.*"),
            Regex(".*Retrofit.*"),
            Regex(".*OkHttp.*"),
            Regex(".*Volley.*")
        )

        // Android framework types
        private val ANDROID_FRAMEWORK_TYPES = setOf(
            "Intent", "Bundle", "Context", "Activity", "Fragment",
            "View", "ViewGroup", "TextView", "Button", "ImageView",
            "RecyclerView", "ListView", "Adapter",
            "SharedPreferences", "ContentResolver", "Cursor",
            "SQLiteDatabase", "ContentValues",
            "Handler", "Looper", "Message",
            "Notification", "PendingIntent", "BroadcastReceiver",
            "Service", "IntentService", "JobService"
        )

        // SQL keywords for database operations
        private val SQL_PATTERNS = listOf(
            Regex("SELECT.*FROM", RegexOption.IGNORE_CASE),
            Regex("INSERT.*INTO", RegexOption.IGNORE_CASE),
            Regex("UPDATE.*SET", RegexOption.IGNORE_CASE),
            Regex("DELETE.*FROM", RegexOption.IGNORE_CASE),
            Regex("CREATE.*TABLE", RegexOption.IGNORE_CASE)
        )

        // URL patterns
        private val URL_PATTERNS = listOf(
            Regex("https?://"),
            Regex("/api/"),
            Regex("/v[0-9]+/")
        )
    }

    /**
     * Check if a method should be skipped from analysis
     */
    fun shouldSkip(node: MethodNode): Boolean {
        // Skip known framework methods
        if (node.parsedMethod.name in SKIP_METHOD_NAMES) return true

        // Skip synthetic methods (access$, lambda$, etc.)
        if (node.isSynthetic) return true

        // Skip very short methods with no signals
        if (node.bodyTokens < 10 && node.signals.isEmpty()) return true

        // Skip getters/setters with trivial body
        val name = node.parsedMethod.name
        if ((name.startsWith("get") || name.startsWith("set") || name.startsWith("is")) &&
            node.bodyTokens < 15) {
            return true
        }

        return false
    }

    /**
     * Score a method node for analysis priority
     * Higher score = should be analyzed earlier
     */
    fun score(node: MethodNode): Int {
        if (shouldSkip(node)) return -1000

        var score = 0
        val method = node.parsedMethod
        val signals = extractSignals(node)

        // External API calls
        score += signals.externalApiCalls.size * 10

        // String literals provide hints
        score += signals.stringLiterals.size * 5

        // Android framework types
        score += signals.androidFrameworkTypes.size * 8

        // SQL patterns
        score += signals.sqlPatterns.size * 15

        // URL patterns
        score += signals.urlPatterns.size * 12

        // SharedPreferences keys
        score += signals.sharedPrefsKeys.size * 10

        // Annotations provide context
        score += signals.annotations.size * 5

        // Lifecycle methods are usually important entry points
        if (signals.isLifecycleMethod) score += 20

        // Bonus for having multiple signals (cross-domain functionality)
        val signalCount = listOf(
            signals.externalApiCalls.isNotEmpty(),
            signals.stringLiterals.isNotEmpty(),
            signals.sqlPatterns.isNotEmpty(),
            signals.urlPatterns.isNotEmpty()
        ).count { it }
        if (signalCount >= 2) score += 10

        // Penalty for being non-obfuscated (less valuable to analyze)
        if (!node.isObfuscated) score -= 20

        // Bonus for reasonable method size (not too short, not too long)
        when {
            method.bodySource.length in 100..500 -> score += 5
            method.bodySource.length > 1000 -> score -= 5
        }

        return score
    }

    /**
     * Extract signals from a method node for scoring and heuristic analysis
     */
    fun extractSignals(node: MethodNode): MethodSignals {
        val method = node.parsedMethod

        val externalApiCalls = mutableListOf<String>()
        val androidFrameworkTypes = mutableListOf<String>()

        // Check method calls for external APIs
        method.methodCalls.forEach { call ->
            val callStr = "${call.targetClass ?: ""}.${call.methodName}"
            if (EXTERNAL_API_PATTERNS.any { it.matches(callStr) }) {
                externalApiCalls.add(callStr)
            }
        }

        // Check parameter types and return type for Android types
        method.parameters.forEach { param ->
            if (param.type in ANDROID_FRAMEWORK_TYPES) {
                androidFrameworkTypes.add(param.type)
            }
        }
        if (method.returnType in ANDROID_FRAMEWORK_TYPES) {
            androidFrameworkTypes.add(method.returnType)
        }

        // Check string literals for patterns
        val sqlPatterns = mutableListOf<String>()
        val urlPatterns = mutableListOf<String>()
        val sharedPrefsKeys = mutableListOf<String>()

        method.stringLiterals.forEach { str ->
            // SQL patterns
            SQL_PATTERNS.forEach { pattern ->
                if (pattern.containsMatchIn(str)) {
                    sqlPatterns.add(str.take(50))
                }
            }

            // URL patterns
            URL_PATTERNS.forEach { pattern ->
                if (pattern.containsMatchIn(str)) {
                    urlPatterns.add(str.take(50))
                }
            }

            // SharedPreferences-like keys
            if (str.matches(Regex("^[a-z_]+(_[a-z]+)*$")) && str.length < 30) {
                sharedPrefsKeys.add(str)
            }
        }

        // Check for lifecycle method
        val isLifecycle = method.name in LIFECYCLE_METHODS

        return MethodSignals(
            externalApiCalls = externalApiCalls,
            stringLiterals = method.stringLiterals,
            androidFrameworkTypes = androidFrameworkTypes.distinct(),
            sqlPatterns = sqlPatterns.distinct(),
            urlPatterns = urlPatterns.distinct(),
            sharedPrefsKeys = sharedPrefsKeys.distinct(),
            annotations = method.annotations,
            isLifecycleMethod = isLifecycle
        )
    }

    /**
     * Sort methods by analysis priority (highest score first)
     */
    fun sortByPriority(nodes: List<MethodNode>): List<MethodNode> {
        return nodes
            .map { it to score(it) }
            .filter { it.second > -1000 }  // Filter out skipped methods
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /**
     * Get methods to analyze from leaf nodes, sorted by priority
     */
    fun getAnalysisCandidates(leafNodes: List<MethodNode>): List<MethodNode> {
        return sortByPriority(leafNodes.filter { it.isObfuscated })
    }
}
