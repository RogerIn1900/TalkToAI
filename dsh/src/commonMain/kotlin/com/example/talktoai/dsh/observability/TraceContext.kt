package com.example.talktoai.dsh.observability

data class TraceContext(
    val connectionGeneration: Long,
    val sessionId: String? = null,
    val requestId: String? = null,
    val seq: Long? = null,
) {
    fun withSession(sessionId: String) = copy(sessionId = sessionId)
    fun withRequest(requestId: String) = copy(requestId = requestId)
    fun withSeq(seq: Long) = copy(seq = seq)

    companion object {
        fun initial(generation: Long) = TraceContext(connectionGeneration = generation)
        val EMPTY = TraceContext(connectionGeneration = 0L)
    }
}

interface DshLogger {
    fun debug(tag: String, ctx: TraceContext, message: String)
    fun info(tag: String, ctx: TraceContext, message: String)
    fun warn(tag: String, ctx: TraceContext, message: String, error: Throwable? = null)
    fun error(tag: String, ctx: TraceContext, message: String, error: Throwable? = null)
}

object NoopLogger : DshLogger {
    override fun debug(tag: String, ctx: TraceContext, message: String) {}
    override fun info(tag: String, ctx: TraceContext, message: String) {}
    override fun warn(tag: String, ctx: TraceContext, message: String, error: Throwable?) {}
    override fun error(tag: String, ctx: TraceContext, message: String, error: Throwable?) {}
}
