"""Compile the production transcript bounds offline and compare with prior semantics."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
ROOT = Path(__file__).resolve().parents[1]
CACHE = Path(os.environ.get('GRADLE_USER_HOME', '/root/.gradle')) / 'caches/modules-2/files-2.1'
JAVA = os.environ.get('JAVA_HOME', '/usr/lib/jvm/java-17-openjdk-arm64') + '/bin/java'
if not Path(JAVA).is_file():
    JAVA = shutil.which('java') or JAVA

def jar(group, name, version):
    return next((CACHE / group / name / version).glob('*/*.jar'))

PROBE = r'''
package dev.aster.ide.agent
import kotlin.random.Random
import kotlin.system.measureNanoTime

data class AgentMessage(val id: String, val role: String, val text: String)
data class AgentSubagentConversation(val id: String, val messages: List<AgentMessage>)
fun oldMessages(messages: List<AgentMessage>): List<AgentMessage> {
    var characters = 0
    return messages.takeLast(60).asReversed().mapNotNull { message ->
        val text = message.text.take(24000)
        if (characters + text.length > 200000) null else message.copy(text = text).also { characters += text.length }
    }.asReversed()
}
fun oldChildren(rows: List<AgentSubagentConversation>): List<AgentSubagentConversation> {
    var remaining = 800000
    return rows.asReversed().map { row ->
        var allowance = minOf(100000, remaining)
        val messages = oldMessages(row.messages).asReversed().mapNotNull { message ->
            if (message.text.length > allowance) null else message.also { allowance -= it.text.length; remaining -= it.text.length }
        }.asReversed()
        row.copy(messages = messages)
    }.asReversed()
}
fun main() {
    val random = Random(731)
    val lengths = listOf(0, 1, 120, 1000, 12000, 23999, 24000, 24001, 30000)
    val texts = lengths.map { "x".repeat(it) }
    fun messages(size: Int) = List(size) { AgentMessage("m$it", if (it % 3 == 0) "tool" else "assistant", texts[random.nextInt(texts.size)]) }
    repeat(1000) {
        val input = messages(random.nextInt(90))
        check(boundedAgentMessages(input) == oldMessages(input)) { "Message semantics differ at case $it" }
    }
    repeat(160) {
        val input = List(random.nextInt(20)) { AgentSubagentConversation("child$it", messages(random.nextInt(80))) }
        val result = boundedSubagentConversations(input)
        check(result == oldChildren(input)) { "Child semantics differ at case $it" }
        check(result.map { it.id } == input.map { it.id })
        check(result.sumOf { it.messages.sumOf { m -> m.text.length } } <= 800000)
    }
    val stable = List(60) { AgentMessage("id$it", "assistant", "stable") }
    val children = List(8) { AgentSubagentConversation("c$it", stable) }
    check(boundedAgentMessages(stable) === stable)
    check(boundedSubagentConversations(children) === children)
    val oversized = listOf(AgentMessage("huge", "assistant", "a".repeat(24001)))
    check(boundedAgentMessages(oversized).single().text.length == 24000)
    check(oversized.single().text.length == 24001)
    repeat(2000) { boundedAgentMessages(stable); oldMessages(stable) }
    var consumed = 0
    val oldNs = measureNanoTime { repeat(10000) { consumed += oldMessages(stable).size } }
    val newNs = measureNanoTime { repeat(10000) { consumed += boundedAgentMessages(stable).size } }
    check(consumed == 1200000)
    println("PASS: 1000 message + 160 child differential cases; identity and input immutability checks")
    println("BENCHMARK stable 60-message transcript, 10000 calls: oldNs=$oldNs newNs=$newNs (host microbenchmark, not Android latency)")
}
'''

def main():
    stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.1.0')
    compiler = [jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.1.0'), stdlib,
                jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.1.0'),
                jar('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
                jar('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
                jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.9.0'),
                jar('org.jetbrains', 'annotations', '13.0')]
    with tempfile.TemporaryDirectory(prefix='aster-transcript-bounds-') as temporary:
        folder = Path(temporary)
        source = folder / 'BoundsProbe.kt'
        source.write_text(PROBE)
        classes = folder / 'classes'
        subprocess.run([JAVA, '-Xmx384m', '-cp', os.pathsep.join(map(str, compiler)),
                        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                        '-jvm-target', '17', '-classpath', str(stdlib), '-d', str(classes), str(source),
                        str(ROOT / 'core/app/src/main/java/dev/aster/ide/agent/AgentTranscriptBounds.kt')], check=True, timeout=90)
        subprocess.run([JAVA, '-Xmx256m', '-cp', os.pathsep.join([str(classes), str(stdlib)]),
                        'dev.aster.ide.agent.BoundsProbeKt'], check=True, timeout=60)

if __name__ == '__main__':
    main()
