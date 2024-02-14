/*
 *  Copyright 2022 UnitTestBot contributors (utbot.org)
 * <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 * <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.jacodb.analysis.ifds2.taint

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging
import org.jacodb.analysis.engine.SummaryStorageImpl
import org.jacodb.analysis.engine.UnitResolver
import org.jacodb.analysis.engine.UnitType
import org.jacodb.analysis.engine.UnknownUnit
import org.jacodb.analysis.graph.reversed
import org.jacodb.analysis.ifds2.ControlEvent
import org.jacodb.analysis.ifds2.Manager
import org.jacodb.analysis.ifds2.QueueEmptinessChanged
import org.jacodb.analysis.ifds2.RunnerImpl
import org.jacodb.analysis.ifds2.pathEdges
import org.jacodb.api.JcMethod
import org.jacodb.api.analysis.JcApplicationGraph
import org.jacodb.taint.configuration.TaintMark
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

private val logger = KotlinLogging.logger {}

class TaintManager(
    private val graph: JcApplicationGraph,
    private val unitResolver: UnitResolver,
) : Manager<TaintFact, TaintEvent> {

    private val methodsForUnit: MutableMap<UnitType, MutableSet<JcMethod>> = hashMapOf()
    private val runnerForUnit: MutableMap<UnitType, TaintRunner> = hashMapOf()
    private val queueIsEmpty: MutableMap<UnitType, Boolean> = ConcurrentHashMap()

    private val summaryEdgesStorage = SummaryStorageImpl<SummaryEdge>()
    private val vulnerabilitiesStorage = SummaryStorageImpl<Vulnerability>()

    private val stopRendezvous = Channel<Unit>(Channel.RENDEZVOUS)

    private fun newRunner(
        unit: UnitType,
    ): TaintRunner {
        check(unit !in runnerForUnit) { "Runner for $unit already exists" }

        logger.debug { "Creating a new runner for $unit" }
        val runner = if (Globals.BIDI_RUNNER) {
            BidiRunner(
                manager = this@TaintManager,
                unitResolver = unitResolver,
                unit = unit,
                { manager ->
                    val analyzer = TaintAnalyzer(graph)
                    RunnerImpl(
                        graph = graph,
                        analyzer = analyzer,
                        manager = manager,
                        unitResolver = unitResolver,
                        unit = unit
                    )
                },
                { manager ->
                    val analyzer = BackwardTaintAnalyzer(graph)
                    RunnerImpl(
                        graph = graph.reversed,
                        analyzer = analyzer,
                        manager = manager,
                        unitResolver = unitResolver,
                        unit = unit
                    )
                }
            )
        } else {
            val analyzer = TaintAnalyzer(graph)
            RunnerImpl(graph, analyzer, this@TaintManager, unitResolver, unit)
        }

        runnerForUnit[unit] = runner
        return runner
    }

    private fun getAllCallees(method: JcMethod): Set<JcMethod> {
        val result: MutableSet<JcMethod> = hashSetOf()
        for (inst in method.flowGraph().instructions) {
            result += graph.callees(inst)
        }
        return result
    }

    private fun addStart(method: JcMethod) {
        logger.info { "Adding start method: $method" }
        val unit = unitResolver.resolve(method)
        if (unit == UnknownUnit) return
        val isNew = methodsForUnit.getOrPut(unit) { hashSetOf() }.add(method)
        if (isNew) {
            for (dep in getAllCallees(method)) {
                addStart(dep)
            }
        }
    }

    @OptIn(ExperimentalTime::class)
    fun analyze(
        startMethods: List<JcMethod>,
    ): List<Vulnerability> = runBlocking(Dispatchers.Default) {
        val timeStart = TimeSource.Monotonic.markNow()

        // Add start methods:
        for (method in startMethods) {
            addStart(method)
        }

        // Determine all units:
        val allUnits = methodsForUnit.keys.toList()
        logger.info {
            "Starting analysis of ${
                methodsForUnit.values.sumOf { it.size }
            } methods in ${allUnits.size} units"
        }

        // Spawn runner jobs:
        val allJobs = allUnits.map { unit ->
            // Create the runner:
            val runner = newRunner(unit)

            // Start the runner:
            launch(start = CoroutineStart.LAZY) {
                val methods = methodsForUnit[unit]!!.toList()
                runner.run(methods)
            }
        }

        // Spawn progress job:
        val progress = launch(Dispatchers.IO) {
            logger.info { "Progress job started" }
            while (isActive) {
                delay(1.seconds)
                logger.info {
                    "Progress: total propagated ${
                        runnerForUnit.values.sumOf { it.pathEdges.size }
                    } path edges"
                }
            }
            logger.info { "Progress job finished" }
        }

        // Spawn stopper job:
        val stopper = launch(Dispatchers.IO) {
            logger.info { "Stopper job started" }
            stopRendezvous.receive()
            logger.info { "Stopping all runners..." }
            allJobs.forEach { it.cancel() }
            logger.info { "Stopper job finished" }
        }

        // Start all runner jobs:
        val timeStartJobs = TimeSource.Monotonic.markNow()
        allJobs.forEach { it.start() }

        // Await all runners:
        withTimeoutOrNull(3600.seconds) {
            allJobs.joinAll()
        } ?: run {
            allJobs.forEach { it.cancel() }
            allJobs.joinAll()
        }
        progress.cancelAndJoin()
        stopper.cancelAndJoin()
        logger.info {
            "All ${allJobs.size} jobs completed in %.1f s".format(
                timeStartJobs.elapsedNow().toDouble(DurationUnit.SECONDS)
            )
        }

        // Extract found vulnerabilities (sinks):
        val foundVulnerabilities = vulnerabilitiesStorage.knownMethods
            .flatMap { method ->
                vulnerabilitiesStorage.getCurrentFacts(method)
            }
        logger.debug { "Total found ${foundVulnerabilities.size} vulnerabilities" }
        for (vulnerability in foundVulnerabilities) {
            logger.debug { "$vulnerability in ${vulnerability.method}" }
        }
        logger.info { "Total sinks: ${foundVulnerabilities.size}" }

        logger.info { "Total propagated ${runnerForUnit.values.sumOf { it.pathEdges.size }} path edges" }

        if (logger.isDebugEnabled) {
            val statsFileName = "stats.csv"
            logger.debug { "Writing stats in '$statsFileName'..." }
            File(statsFileName).outputStream().bufferedWriter().use { writer ->
                val sep = ";"
                writer.write(listOf("classname", "cwe", "method", "sink", "fact").joinToString(sep) + "\n")
                for (vulnerability in foundVulnerabilities) {
                    val m = vulnerability.method
                    if (vulnerability.rule != null) {
                        for (cwe in vulnerability.rule.cwe) {
                            writer.write(
                                listOf(
                                    m.enclosingClass.simpleName,
                                    cwe,
                                    m.name,
                                    vulnerability.sink.statement,
                                    vulnerability.sink.fact
                                ).joinToString(sep) { "\"$it\"" } + "\n")
                        }
                    } else if (
                        vulnerability.sink.fact is Tainted
                        && vulnerability.sink.fact.mark == TaintMark.NULLNESS
                    ) {
                        val cwe = 476
                        writer.write(
                            listOf(
                                m.enclosingClass.simpleName,
                                cwe,
                                m.name,
                                vulnerability.sink.statement,
                                vulnerability.sink.fact
                            ).joinToString(sep) { "\"$it\"" } + "\n")
                    } else {
                        logger.warn { "Bad vulnerability without rule: $vulnerability" }
                    }
                }
            }
        }

        logger.info {
            "Analysis done in %.1f s".format(
                timeStart.elapsedNow().toDouble(DurationUnit.SECONDS)
            )
        }
        foundVulnerabilities
    }

    override fun handleEvent(event: TaintEvent) {
        when (event) {
            is NewSummaryEdge -> {
                summaryEdgesStorage.add(SummaryEdge(event.edge))
            }

            is NewVulnerability -> {
                vulnerabilitiesStorage.add(event.vulnerability)
            }

            is EdgeForOtherRunner -> {
                val method = event.edge.method
                val unit = unitResolver.resolve(method)
                val otherRunner = runnerForUnit[unit] ?: run {
                    error("No runner for $unit")
                    // logger.trace { "Ignoring event=$event for non-existing runner for unit=$unit" }
                    // return
                }
                otherRunner.submitNewEdge(event.edge)
            }
        }
    }

    override fun handleControlEvent(event: ControlEvent) {
        when (event) {
            is QueueEmptinessChanged -> {
                logger.trace { "Runner ${event.runner.unit} is empty: ${event.isEmpty}" }
                queueIsEmpty[event.runner.unit] = event.isEmpty
                if (event.isEmpty) {
                    if (runnerForUnit.keys.all { queueIsEmpty[it] == true }) {
                        logger.debug { "All runners are empty" }
                        stopRendezvous.trySend(Unit).getOrNull()
                    }
                }
            }
        }
    }

    override fun subscribeOnSummaryEdges(
        method: JcMethod,
        scope: CoroutineScope,
        handler: (TaintEdge) -> Unit,
    ) {
        summaryEdgesStorage
            .getFacts(method)
            .onEach { handler(it.edge) }
            .launchIn(scope)
    }
}

fun runTaintAnalysis(
    graph: JcApplicationGraph,
    unitResolver: UnitResolver,
    startMethods: List<JcMethod>,
): List<Vulnerability> {
    val manager = TaintManager(graph, unitResolver)
    return manager.analyze(startMethods)
}
