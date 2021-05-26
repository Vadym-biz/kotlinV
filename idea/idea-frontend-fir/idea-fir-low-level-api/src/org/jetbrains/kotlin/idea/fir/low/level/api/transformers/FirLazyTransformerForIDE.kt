/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.fir.low.level.api.transformers

import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.idea.fir.low.level.api.api.FirDeclarationUntypedDesignation

internal interface FirLazyTransformerForIDE {
    fun transformDeclaration()

    companion object {
        private object ResolvePhaseWithForAllDeclarationsKey : FirDeclarationDataKey()

        private var FirDeclaration.resolvePhaseWithForAllDeclarationsAttr: FirResolvePhase?
                by FirDeclarationDataRegistry.data(ResolvePhaseWithForAllDeclarationsKey)

        var FirDeclaration.resolvePhaseWithForAllDeclarations: FirResolvePhase
            get() = (resolvePhaseWithForAllDeclarationsAttr ?: FirResolvePhase.RAW_FIR).also { allPhase ->
//                check(allPhase <= resolvePhase) {
//                    "Invalid resolvePhaseWithForAllDeclarations get $resolvePhaseWithForAllDeclarationsAttr while resolvePhase is $resolvePhase"
//                }
            }
            set(value) {
//                check(value <= resolvePhase) {
//                    "Invalid resolvePhaseWithForAllDeclarations set resolvePhaseWithForAllDeclarationsAttr while resolvePhase is $resolvePhase"
//                }
                resolvePhaseWithForAllDeclarationsAttr = value
            }

        fun FirDeclaration.updateResolvedForAllDeclarations(phase: FirResolvePhase) {
            val allDeclaration = resolvePhaseWithForAllDeclarations
            if (allDeclaration < phase) {
                resolvePhaseWithForAllDeclarations = phase
            }
        }

        fun FirDeclarationUntypedDesignation.resolvePhaseForAllDeclarations(isOnAirResolve: Boolean): FirResolvePhase {
            val allContaining = toSequence(includeTarget = true)
                .maxByOrNull { it.resolvePhaseWithForAllDeclarations }
                ?.resolvePhaseWithForAllDeclarations
                ?: FirResolvePhase.RAW_FIR
            return if (isOnAirResolve) minOf(declaration.resolvePhase, allContaining) else allContaining
        }


        fun FirDeclarationUntypedDesignation.isResolvedForAllDeclarations(phase: FirResolvePhase, isOnAirResolve: Boolean) =
            resolvePhaseForAllDeclarations(isOnAirResolve) >= phase

        val DUMMY = object : FirLazyTransformerForIDE {
            override fun transformDeclaration() = Unit
        }
    }

    fun <T : FirDeclaration> T.containingDeclarations() = when (this) {
        is FirClass<*> -> declarations
        is FirAnonymousObject -> declarations
        else -> emptyList()
    }
}