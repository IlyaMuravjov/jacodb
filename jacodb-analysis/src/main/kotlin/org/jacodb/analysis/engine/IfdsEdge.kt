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

package org.jacodb.analysis.engine

import org.jacodb.api.core.CoreMethod
import org.jacodb.api.core.cfg.CoreInst
import org.jacodb.api.core.cfg.CoreInstLocation

/**
 * Represents a directed (from [u] to [v]) edge between two ifds vertices
 */
data class IfdsEdge<Method, Location, Statement>(
    val u: IfdsVertex<Method, Location, Statement>,
    val v: IfdsVertex<Method, Location, Statement>
) where Method : CoreMethod<Statement>,
        Location : CoreInstLocation<Method>,
        Statement : CoreInst<Location, Method, *> {
    init {
        require(u.method == v.method)
    }

    val method: Method
        get() = u.method
}

sealed interface PredecessorKind {
    object NoPredecessor : PredecessorKind
    object Unknown : PredecessorKind
    object Sequent : PredecessorKind
    object CallToStart : PredecessorKind
    class ThroughSummary<Method, Location, Statement>(
        val summaryEdge: IfdsEdge<Method, Location, Statement>
    ) : PredecessorKind where Method : CoreMethod<Statement>,
                              Location : CoreInstLocation<Method>,
                              Statement : CoreInst<Location, Method, *>
}

/**
 * Contains info about predecessor of path edge.
 * Used mainly to restore traces.
 */
data class PathEdgePredecessor<Method, Location, Statement>(
    val predEdge: IfdsEdge<Method, Location, Statement>,
    val kind: PredecessorKind
) where Method : CoreMethod<Statement>,
        Location : CoreInstLocation<Method>,
        Statement : CoreInst<Location, Method, *>