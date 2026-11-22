package dev.aster.agent

enum class AgentSetupPhase(val title: String, @androidx.annotation.StringRes val titleResource: Int) {
    WORKSPACE("准备工作区与会话", R.string.agent_setup_workspace),
    TERMUX("准备内置 Termux", R.string.agent_setup_termux),
    NODE("安装 Agent 运行环境", R.string.agent_setup_node),
    BRIDGE("启动 Python 工具桥接", R.string.agent_setup_bridge),
    ENGINES("检查底层引擎", R.string.agent_setup_engines),
    READY("本地引擎检查通过（未测试模型接口）", R.string.agent_setup_ready),
    FAILED("环境准备失败", R.string.agent_setup_failed),
    CANCELLED("已停止环境准备", R.string.agent_setup_cancelled)
}
data class AgentSetupState(val phase: AgentSetupPhase = AgentSetupPhase.WORKSPACE, val error: String? = null, val stoppedAt: AgentSetupPhase? = null)
enum class AgentWebPhase { LOADING, READY, FAILED }
data class AgentWebState(val phase: AgentWebPhase = AgentWebPhase.LOADING, val message: String = "")

/** No model configuration is needed to install the local runtime. The UI observes each step. */
internal object AgentStartup {
    const val REVISION = "agent-permissions-startup-fix4"
    suspend fun prepare(
        workspace: suspend () -> Unit,
        runtime: suspend () -> Boolean,
        installNode: suspend () -> Unit,
        engines: suspend () -> Unit,
        stage: (AgentSetupPhase) -> Unit,
        bridge: suspend () -> Unit = {}
    ) {
        stage(AgentSetupPhase.WORKSPACE); workspace()
        stage(AgentSetupPhase.TERMUX)
        if (runtime()) { stage(AgentSetupPhase.NODE); installNode() }
        stage(AgentSetupPhase.BRIDGE); bridge()
        stage(AgentSetupPhase.ENGINES); engines()
        stage(AgentSetupPhase.READY)
    }
}
