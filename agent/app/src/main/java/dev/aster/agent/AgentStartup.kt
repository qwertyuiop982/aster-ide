package dev.aster.agent

enum class AgentSetupPhase(val title: String) {
    WORKSPACE("准备工作区与会话"), TERMUX("准备内置 Termux"), NODE("安装 Agent 运行环境"),
    BRIDGE("启动 Python 工具桥接"), ENGINES("检查底层引擎"), READY("本地引擎检查通过（未测试模型接口）"), FAILED("环境准备失败"), CANCELLED("已停止环境准备")
}
data class AgentSetupState(val phase: AgentSetupPhase = AgentSetupPhase.WORKSPACE, val error: String? = null, val stoppedAt: AgentSetupPhase? = null)
enum class AgentWebPhase { LOADING, READY, FAILED }
data class AgentWebState(val phase: AgentWebPhase = AgentWebPhase.LOADING, val message: String = "正在加载 Agent 界面…")

/** No model configuration is needed to install the local runtime. The UI observes each step. */
internal object AgentStartup {
    const val REVISION = "agent-startup-fix3"
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
