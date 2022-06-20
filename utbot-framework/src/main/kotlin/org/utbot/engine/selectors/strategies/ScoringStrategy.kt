package org.utbot.engine.selectors.strategies

import org.utbot.engine.*
import org.utbot.engine.pc.UtSolverStatus
import org.utbot.engine.pc.UtSolverStatusSAT
import org.utbot.framework.plugin.api.UtModel

interface ScoringStrategy {
    fun score(executionState: ExecutionState): Int

    operator fun get(state: ExecutionState): Int
}


class BasicScoringStrategy(
    val targets: Map<LocalVariable, UtModel>,
    protected val resolverBuilder: (memory: Memory, holder: UtSolverStatusSAT) -> Resolver
) : ScoringStrategy {
    private val stateModels = hashMapOf<ExecutionState, UtSolverStatus>()
    private val scores = hashMapOf<ExecutionState, Int>()

    override fun get(state: ExecutionState): Int = scores.getValue(state)

    override fun score(executionState: ExecutionState): Int = scores.getOrPut(executionState) {
        stateModels[executionState] = executionState.solver.check(respectSoft = false)
        return (stateModels[executionState] as? UtSolverStatusSAT)?.let { holder ->
            val resolver = resolverBuilder(executionState.memory, holder)
            val executionStack = executionState.executionStack.last()
            val locals = executionStack.localVariableMemory
            val localModels = locals.localsMap.mapValues { resolver.resolveModel(it.value) }
            0
        } ?: -1
    }
}