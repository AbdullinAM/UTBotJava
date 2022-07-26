package org.utbot.framework.synthesis

import mu.KotlinLogging
import org.utbot.engine.ResolvedModels
import org.utbot.framework.modifications.StatementsStorage
import org.utbot.framework.plugin.api.*
import org.utbot.framework.plugin.api.util.intClassId
import org.utbot.framework.plugin.api.util.isArray
import org.utbot.framework.plugin.api.util.isPrimitive
import org.utbot.framework.plugin.api.util.objectClassId
import org.utbot.framework.synthesis.postcondition.constructors.toSoot

internal fun Collection<ClassId>.expandable() = filter { !it.isArray && !it.isPrimitive }.toSet()

class ConstrainedSynthesizer(
    val parameters: ResolvedModels,
    val depth: Int = 4
) {
    companion object {
        private val logger = KotlinLogging.logger("ConstrainedSynthesizer")
        private var attempts = 0
        private var successes = 0


        fun stats(): String = buildString {
            appendLine("Total attempts: $attempts")
            appendLine("Successful attempts $successes")
            appendLine("Success rate: ${String.format("%.2f", successes.toDouble() / attempts)}")
        }

        fun success() {
            ++attempts
            ++successes
            logger.debug { stats() }
        }

        fun failure() {
            ++attempts
            logger.debug { stats() }
        }
    }

    private val logger = KotlinLogging.logger("ConstrainedSynthesizer")
    private val statementStorage = StatementsStorage().also { storage ->
        storage.update(parameters.parameters.map { it.classId }.expandable())
    }

    private val queue = MultipleSynthesisUnitQueue(
        parameters,
        LeafExpanderProducer(statementStorage),
        depth
    )
    private val unitChecker = ConstrainedSynthesisUnitChecker(objectClassId.toSoot())

    fun synthesize(): List<UtModel>? {
        while (!queue.isEmpty()) {
            val units = queue.poll()
            logger.debug { "Visiting state: $units" }

            val assembleModel = unitChecker.tryGenerate(units, parameters)
            if (assembleModel != null) {
                logger.debug { "Found $assembleModel" }
                success()
                return assembleModel
            }
        }
        failure()
        return null
    }
}

fun List<UtModel>.toSynthesisUnits() = map {
    when (it) {
        is UtNullModel -> NullUnit(it.classId)
        is UtPrimitiveConstraintModel -> ObjectUnit(it.classId)
        is UtReferenceConstraintModel -> ObjectUnit(it.classId)
        is UtArrayConstraintModel -> ArrayUnit(
            it.classId,
            elements = it.indices.map { index -> ObjectUnit(intClassId) to ObjectUnit(index.first().classId) }
        )
        is UtReferenceToConstraintModel -> ReferenceToUnit(it.classId, indexOf(it.reference))
        else -> error("Only UtSynthesisModel supported")
    }
}

class MultipleSynthesisUnitQueue(
    val parameters: ResolvedModels,
    val producer: LeafExpanderProducer,
    val depth: Int
) {
    private val inits = parameters.parameters.toSynthesisUnits()
    private val queues = inits.map { init -> initializeQueue(init) }.toMutableList()
    private var hasNext = true

    fun isEmpty(): Boolean = !hasNext

    fun poll(): List<SynthesisUnit> {
        val results = queues.map { it.peek()!! }
        increase()
        return results
    }

    private fun increase() {
        var shouldGoNext = true
        var index = 0
        while (shouldGoNext) {
            pollUntilFullyDefined(queues[index])
            if (queues[index].isEmpty()) {
                queues[index] = initializeQueue(inits[index])
                index++
                if (index >= queues.size) {
                    hasNext = false
                    return
                }
                shouldGoNext = true
            } else {
                shouldGoNext = false
            }
        }
    }

    private fun initializeQueue(unit: SynthesisUnit): SynthesisUnitQueue = SynthesisUnitQueue(depth).also { queue ->
        when {
            unit is ObjectUnit && unit.isPrimitive() -> queue.push(unit)
            unit is NullUnit -> queue.push(unit)
            unit is ArrayUnit -> queue.push(unit)
            unit is ReferenceToUnit -> queue.push(unit)
            else -> producer.produce(unit).forEach { queue.push(it) }
        }
        peekUntilFullyDefined(queue)
    }

    private fun peekUntilFullyDefined(queue: SynthesisUnitQueue) {
        if (queue.isEmpty()) return
        while (!queue.peek()!!.isFullyDefined()) {
            val state = queue.poll()!!
            producer.produce(state).forEach { queue.push(it) }
            if (queue.isEmpty()) return
        }
    }

    private fun pollUntilFullyDefined(queue: SynthesisUnitQueue) {
        val state = queue.poll()!!
        producer.produce(state).forEach { queue.push(it) }
        peekUntilFullyDefined(queue)
    }
}