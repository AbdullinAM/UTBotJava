package org.utbot.framework.synthesis

import mu.KotlinLogging
import org.utbot.engine.ResolvedModels
import org.utbot.engine.selectors.strategies.ScoringStrategyBuilder
import org.utbot.engine.variable
import org.utbot.framework.PathSelectorType
import org.utbot.framework.UtSettings
import org.utbot.framework.UtSettings.enableSynthesis
import org.utbot.framework.plugin.api.MockStrategyApi
import org.utbot.framework.plugin.api.UtBotTestCaseGenerator
import org.utbot.framework.plugin.api.UtModel
import org.utbot.framework.synthesis.postcondition.constructors.ConstraintBasedPostConditionConstructor
import org.utbot.framework.synthesis.postcondition.constructors.PostConditionConstructor
import soot.SootClass

class ConstrainedSynthesisUnitChecker(
    val declaringClass: SootClass,
) {
    private val logger = KotlinLogging.logger("ConstrainedSynthesisUnitChecker")
    private val synthesizer = ConstrainedJimpleMethodSynthesizer()

    var id = 0

    fun tryGenerate(units: List<SynthesisUnit>, parameters: ResolvedModels): List<UtModel>? {
        if (units.any { !it.isFullyDefined() }) return null

        val context = synthesizer.synthesize(units)
        val method = context.method("\$initializer_${id++}", declaringClass)

        logger.error { "Generated constraint method" }
        logger.error { method.activeBody }

        System.err.println("Running engine...")
        val scoringStrategy = ScoringStrategyBuilder(
            emptyMap()
        )
        val execution = withPathSelector(PathSelectorType.INHERITORS_SELECTOR) {
            enableSynthesis = false
            UtBotTestCaseGenerator.generateWithPostCondition(
                method,
                MockStrategyApi.NO_MOCKS,
                ConstraintBasedPostConditionConstructor(
                    parameters,
                    units.map { context.unitToParameter[it] },
                    units.map { context.unitToLocal[it]?.variable }),
                scoringStrategy
            ).firstOrNull().also {
                enableSynthesis = true
            }
        } ?: return null


        logger.error { execution }
        return context.resolve(listOfNotNull(execution.stateBefore.thisInstance) + execution.stateBefore.parameters)
    }

    private fun <T> withPathSelector(pathSelectorType: PathSelectorType, body: () -> T): T {
        val oldSelector = UtSettings.pathSelectorType
        UtSettings.pathSelectorType = pathSelectorType
        val res = body()
        UtSettings.pathSelectorType = oldSelector
        return res
    }
}
