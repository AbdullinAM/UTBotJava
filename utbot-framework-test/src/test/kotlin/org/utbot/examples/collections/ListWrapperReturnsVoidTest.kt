package org.utbot.examples.collections

import org.junit.jupiter.api.Disabled
import org.utbot.framework.plugin.api.CodegenLanguage
import org.junit.jupiter.api.Test
import org.utbot.testcheckers.eq
import org.utbot.testing.CodeGeneration
import org.utbot.testing.DoNotCalculate
import org.utbot.testing.UtValueTestCaseChecker
import org.utbot.testing.isException

// TODO failed Kotlin compilation ($ in function names, generics) SAT-1220 SAT-1332
@Disabled("Java 11 transition")
internal class ListWrapperReturnsVoidTest : UtValueTestCaseChecker(
    testClass = ListWrapperReturnsVoidExample::class,
    testCodeGeneration = true,
    pipelines = listOf(
        TestLastStage(CodegenLanguage.JAVA),
        TestLastStage(CodegenLanguage.KOTLIN, CodeGeneration)
    )
) {
    @Test
    fun testRunForEach() {
        checkWithException(
            ListWrapperReturnsVoidExample::runForEach,
            eq(4),
            { l, r -> l == null && r.isException<NullPointerException>() },
            { l, r -> l.isEmpty() && r.getOrThrow() == 0 },
            { l, r -> l.isNotEmpty() && l.all { it != null } && r.getOrThrow() == 0 },
            { l, r -> l.isNotEmpty() && l.any { it == null } && r.getOrThrow() > 0 },
            coverage = DoNotCalculate
        )
    }

    @Test
    fun testSumPositiveForEach() {
        checkWithException(
            ListWrapperReturnsVoidExample::sumPositiveForEach,
            eq(5),
            { l, r -> l == null && r.isException<NullPointerException>() },
            { l, r -> l.isEmpty() && r.getOrThrow() == 0 },
            { l, r -> l.isNotEmpty() && l.any { it == null } && r.isException<NullPointerException>() },
            { l, r -> l.isNotEmpty() && l.any { it <= 0 } && r.getOrThrow() == l.filter { it > 0 }.sum() },
            { l, r -> l.isNotEmpty() && l.any { it > 0 } && r.getOrThrow() == l.filter { it > 0 }.sum() }
        )
    }
}