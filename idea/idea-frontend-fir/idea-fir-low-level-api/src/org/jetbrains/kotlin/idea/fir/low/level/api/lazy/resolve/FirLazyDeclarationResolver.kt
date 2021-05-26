/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.fir.low.level.api.lazy.resolve

import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.fir.*
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.providers.FirProvider
import org.jetbrains.kotlin.fir.resolve.symbolProvider
import org.jetbrains.kotlin.fir.resolve.transformers.FirProviderInterceptor
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.FirTowerDataContextCollector
import org.jetbrains.kotlin.idea.fir.low.level.api.api.*
import org.jetbrains.kotlin.idea.fir.low.level.api.element.builder.getNonLocalContainingOrThisDeclaration
import org.jetbrains.kotlin.idea.fir.low.level.api.file.builder.FirFileBuilder
import org.jetbrains.kotlin.idea.fir.low.level.api.file.builder.ModuleFileCache
import org.jetbrains.kotlin.idea.fir.low.level.api.providers.firIdeProvider
import org.jetbrains.kotlin.idea.fir.low.level.api.transformers.*
import org.jetbrains.kotlin.idea.fir.low.level.api.transformers.FirLazyTransformerForIDE.Companion.resolvePhaseForAllDeclarations
import org.jetbrains.kotlin.idea.fir.low.level.api.util.*
import org.jetbrains.kotlin.idea.util.ifTrue
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

enum class ResolveType {
    FileAnnotations,
    CallableReturnType,
    ClassSuperTypes,
    DeclarationStatus,
    ValueParametersTypes,
    TypeParametersTypes,
    AnnotationType,
    AnnotationParameters,
    CallableBodyResolve,
    ResolveForMemberScope,
    ResolveForSuperMembers,
    CallableContracts,
    NoResolve,
}

internal class FirLazyDeclarationResolver(
    private val firFileBuilder: FirFileBuilder
) {
    fun lazyResolveDeclaration(
        firDeclaration: FirDeclaration,
        moduleFileCache: ModuleFileCache,
        toResolveType: ResolveType,
        scopeSession: ScopeSession = ScopeSession(),
        checkPCE: Boolean = false,
    ) {
        check(toResolveType == ResolveType.CallableReturnType)
        lazyResolveDeclaration(firDeclaration, moduleFileCache, FirResolvePhase.IMPLICIT_TYPES_BODY_RESOLVE, scopeSession, checkPCE)
//        if (firDeclaration.resolvePhase == FirResolvePhase.BODY_RESOLVE) return
//
//        val firFile = firDeclaration.getContainingFile()
//            ?: error("FirFile was not found for\n${firDeclaration.render()}")
//
//        fun tryResolveAsLocalDeclaration(): Boolean {
//            val nonLocalDeclarationToResolve = firDeclaration.getNonLocalDeclarationToResolve(
//                firFile.moduleData.session.firIdeProvider, moduleFileCache
//            )
//
//            if (firDeclaration != nonLocalDeclarationToResolve) {
//                runLazyDesignatedResolveWithoutLock(
//                    designation = nonLocalDeclarationToResolve.collectDesignation(firFile),
//                    scopeSession = scopeSession,
//                    moduleFileCache = moduleFileCache,
//                    toPhase = FirResolvePhase.BODY_RESOLVE,
//                    checkPCE = checkPCE,
//                )
//                return true
//            }
//            return false
//        }
//
//        FirFileBuilder.runCustomResolveUnderLock(firFile, moduleFileCache) {
//            if (tryResolveAsLocalDeclaration()) return@runCustomResolveUnderLock
//
//            when (toResolveType) {
//                ResolveType.CallableReturnType -> {
//                    require(firDeclaration is FirCallableDeclaration<*>) {
//                        firDeclaration.errorMessage<FirCallableDeclaration<*>>(ResolveType.CallableReturnType)
//                    }
//                    resolveToCallableReturnType(
//                        firDeclaration = firDeclaration,
//                        firFile = firFile,
//                        scopeSession = scopeSession,
//                        moduleFileCache = moduleFileCache, checkPCE = false)
//                }
//                else -> error("")
//            }
//        }
    }

    private inline fun <reified T : FirDeclaration> FirDeclaration.errorMessage(type: ResolveType) =
        "$type require to be called on ${T::class.simpleName} but ${this::class.simpleName}"

//    private fun FirDeclarationUntypedDesignation.minPhase(): FirResolvePhase {
//        val pathOrDeclarationPhase = path.minByOrNull { it.resolvePhase }?.resolvePhase ?: declaration.resolvePhase
//        return minOf(pathOrDeclarationPhase, declaration.resolvePhase)
//    }

//    fun resolveToCallableBodyResolve(
//        firDeclaration: FirCallableDeclaration<*>,
//        firFile: FirFile,
//        scopeSession: ScopeSession = ScopeSession(),
//        moduleFileCache: ModuleFileCache,
//        checkPCE: Boolean,
//    ) {
//        if (firDeclaration.resolvePhase == FirResolvePhase.BODY_RESOLVE) return
//
//        val designation = firDeclaration.collectDesignation(firFile)
//        check(firDeclaration.resolvePhase < FirResolvePhase.BODY_RESOLVE) { "XXX" }
//
//        runLazyDesignatedResolveWithoutLock(
//            designation = designation,
//            scopeSession = scopeSession,
//            moduleFileCache = moduleFileCache,
//            toPhase = FirResolvePhase.BODY_RESOLVE,
//            checkPCE = checkPCE,
//        )
//
//        check(firDeclaration.resolvePhase >= FirResolvePhase.BODY_RESOLVE) { "XXX" }
//    }

//    fun resolveToCallableReturnType(
//        firDeclaration: FirCallableDeclaration<*>,
//        firFile: FirFile,
//        scopeSession: ScopeSession = ScopeSession(),
//        moduleFileCache: ModuleFileCache,
//        checkPCE: Boolean,
//    ) {
//        if (firDeclaration.returnTypeRef is FirResolvedTypeRef) return
//
//        val targetPhase =
//            if (firDeclaration.returnTypeRef is FirImplicitTypeRef) FirResolvePhase.IMPLICIT_TYPES_BODY_RESOLVE else FirResolvePhase.TYPES
//
//        val designation = firDeclaration.collectDesignation(firFile)
//        check(firDeclaration.resolvePhase < targetPhase) { "XXX" }
//
//        runLazyDesignatedResolveWithoutLock(
//            designation = designation,
//            scopeSession = scopeSession,
//            moduleFileCache = moduleFileCache,
//            toPhase = targetPhase,
//            checkPCE = checkPCE,
//        )
//
//        check(firDeclaration.resolvePhase >= targetPhase) { "XXX" }
//        check(firDeclaration.returnTypeRef is FirResolvedTypeRef)
//    }

//    private fun runLazyDesignatedResolveWithoutLock(
//        designation: FirDeclarationUntypedDesignationWithFile,
//        scopeSession: ScopeSession,
//        moduleFileCache: ModuleFileCache,
//        toPhase: FirResolvePhase,
//        checkPCE: Boolean,
//        isOnAirResolve: Boolean = false,
//        towerDataContextCollector: FirTowerDataContextCollector? = null,
//    ) {
//        check(!designation.isLocalDesignation) { "Could not resolve local designation" }
//
//        //This needed to override standard symbol resolve in supertype transformer with adding on-air created symbols
//        val firProviderInterceptor = isOnAirResolve.ifTrue {
//            FirProviderInterceptorForIDE.createForFirElement(
//                session = designation.firFile.moduleData.session,
//                firFile = designation.firFile,
//                element = designation.declaration
//            )
//        }
//
//        var currentPhase = designation.minPhase()
//
//        while (currentPhase < toPhase) {
//            currentPhase = currentPhase.next
//            if (currentPhase.pluginPhase) continue
//            if (checkPCE) checkCanceled()
//            FirLazyBodiesCalculator.calculateLazyBodiesInsideIfNeeded(designation, currentPhase)
//
//            runLazyResolvePhase(
//                phase = currentPhase,
//                scopeSession = scopeSession,
//                moduleFileCache = moduleFileCache,
//                designation = designation,
//                towerDataContextCollector = towerDataContextCollector,
//                firProviderInterceptor = firProviderInterceptor,
//                checkPCE = checkPCE,
//            )
//        }
//    }


    /**
     * Fully resolve file annotations (synchronized)
     * @see resolveFileAnnotationsWithoutLock not synchronized
     */
    fun resolveFileAnnotations(
        firFile: FirFile,
        annotations: List<FirAnnotationCall>,
        moduleFileCache: ModuleFileCache,
        scopeSession: ScopeSession = ScopeSession(),
        collector: FirTowerDataContextCollector? = null,
    ) {
        lazyResolveDeclaration(
            declaration = firFile,
            moduleFileCache = moduleFileCache,
            toPhase = FirResolvePhase.IMPORTS,
            checkPCE = false,
        )
        FirFileBuilder.runCustomResolveUnderLock(firFile, moduleFileCache) {
            resolveFileAnnotationsWithoutLock(firFile, annotations, scopeSession, collector)
        }
    }

    /**
     * Fully resolve file annotations (not synchronized)
     * @see resolveFileAnnotations synchronized version
     */
    private fun resolveFileAnnotationsWithoutLock(
        firFile: FirFile,
        annotations: List<FirAnnotationCall>,
        scopeSession: ScopeSession,
        collector: FirTowerDataContextCollector? = null,
    ) {
        FirFileAnnotationsResolveTransformer(
            firFile = firFile,
            annotations = annotations,
            session = firFile.moduleData.session,
            scopeSession = scopeSession,
            firTowerDataContextCollector = collector,
        ).transformDeclaration()
    }

    private fun getResolvableDeclaration(declaration: FirDeclaration, moduleFileCache: ModuleFileCache): FirDeclaration {
        if (declaration is FirFile) return declaration

        val ktDeclaration = (declaration.psi as? KtDeclaration) ?: run {
            (declaration.source as? FirFakeSourceElement<*>).psi?.let {
                PsiTreeUtil.getParentOfType(it, KtDeclaration::class.java)
            }
        }
        check(ktDeclaration is KtDeclaration) {
            "FirDeclaration should have a PSI of type KtDeclaration"
        }

        val x = ktDeclaration.getNonLocalContainingOrThisDeclaration()

        if (declaration is FirAnonymousObject) {
            check(ktDeclaration is KtObjectDeclaration)
            val nonLocalDeclaration = ktDeclaration.getNonLocalContainingOrThisDeclaration()
            check(nonLocalDeclaration != null) { "Cannot find non-local declaration for anonymous object" }
            return nonLocalDeclaration.findSourceNonLocalFirDeclaration(
                firFileBuilder = firFileBuilder,
                firSymbolProvider = declaration.moduleData.session.symbolProvider,
                moduleFileCache = moduleFileCache
            )
        }

        if (declaration is FirPropertyAccessor || declaration is FirTypeParameter || declaration is FirValueParameter) {
            val ktContainingResolvableDeclaration = when (val psi = declaration.psi) {
                is KtPropertyAccessor -> psi.property
                is KtProperty -> psi
                is KtParameter, is KtTypeParameter -> psi.getNonLocalContainingOrThisDeclaration()
                    ?: error("Cannot find containing declaration for KtParameter")
                is KtCallExpression -> {
                    check(declaration.source?.kind == FirFakeSourceElementKind.DefaultAccessor)
                    val delegationCall = psi.parent as KtPropertyDelegate
                    delegationCall.parent as KtProperty
                }
                null -> error("Cannot find containing declaration for KtParameter")
                else -> error("Invalid source of property accessor ${psi::class}")
            }

            return ktContainingResolvableDeclaration.findSourceNonLocalFirDeclaration(
                firFileBuilder = firFileBuilder,
                firSymbolProvider = declaration.moduleData.session.symbolProvider,
                moduleFileCache = moduleFileCache
            )
        }

        return declaration
    }

    private fun FirDeclaration.isAvailableForResolve(): Boolean = when (origin) {
        is FirDeclarationOrigin.Source, is FirDeclarationOrigin.Synthetic, is FirDeclarationOrigin.SubstitutionOverride -> true
        else -> false.also { check(resolvePhase == FirResolvePhase.BODY_RESOLVE) }
    }

    /**
     * Run partially designated resolve that resolve declaration into last file-wise resolve and then resolve a designation (synchronized)
     * @see LAST_NON_LAZY_PHASE is the last file-wise resolve
     * @see lazyDesignatedResolveDeclaration designated resolve
     * @see runLazyResolveWithoutLock (not synchronized)
     */
    fun lazyResolveDeclaration(
        declaration: FirDeclaration,
        moduleFileCache: ModuleFileCache,
        toPhase: FirResolvePhase,
        scopeSession: ScopeSession = ScopeSession(),
        checkPCE: Boolean = false,
    ) {
        if (toPhase == FirResolvePhase.RAW_FIR) return
        if (!declaration.isAvailableForResolve()) return

        //TODO
        if (declaration is FirFile) {
            check(toPhase <= FirResolvePhase.TYPES)
            FirFileBuilder.runCustomResolveWithPCECheck(declaration, moduleFileCache) {
                runLazyResolveWithoutLock(
                    firDeclarationToResolve = declaration,
                    moduleFileCache = moduleFileCache,
                    containerFirFile = declaration,
                    provider = declaration.moduleData.session.firIdeProvider,
                    scopeSession = scopeSession,
                    fromPhase = FirResolvePhase.RAW_FIR,
                    toPhase = FirResolvePhase.IMPORTS,
                    checkPCE = true,
                )
            }
            //Temporary resolve file only for annotations
            if (toPhase > FirResolvePhase.IMPORTS) {
                resolveFileAnnotations(declaration, declaration.annotations, moduleFileCache, scopeSession)
            }
            return
        }

        val provider = declaration.moduleData.session.firIdeProvider
        val resolvableDeclaration = declaration.getNonLocalDeclarationToResolve(provider, moduleFileCache)

        val designation = resolvableDeclaration.collectDesignation()
        val resolvePhase = designation.resolvePhaseForAllDeclarations(isOnAirResolve = false)
        if (resolvePhase >= toPhase) return

        // Lazy since we want to read the resolve phase inside the lock. Otherwise, we may run the same resolve phase multiple times. See
        // KT-45121
        val fromPhase: FirResolvePhase by lazy(LazyThreadSafetyMode.NONE) {
            designation.resolvePhaseForAllDeclarations(isOnAirResolve = false)
        }

        val firFile = resolvableDeclaration.getContainingFile()
            ?: error("FirFile was not found for\n${declaration.render()}")

        if (checkPCE) {
            FirFileBuilder.runCustomResolveWithPCECheck(firFile, moduleFileCache) {
                runLazyResolveWithoutLock(
                    firDeclarationToResolve = declaration,
                    moduleFileCache = moduleFileCache,
                    containerFirFile = firFile,
                    provider = provider,
                    scopeSession = scopeSession,
                    fromPhase = fromPhase,
                    toPhase = toPhase,
                    checkPCE = true,
                )
            }
        } else {
            FirFileBuilder.runCustomResolveUnderLock(firFile, moduleFileCache) {
                executeWithoutPCE {
                    runLazyResolveWithoutLock(
                        firDeclarationToResolve = declaration,
                        moduleFileCache = moduleFileCache,
                        containerFirFile = firFile,
                        provider = provider,
                        scopeSession = scopeSession,
                        fromPhase = fromPhase,
                        toPhase = toPhase,
                        checkPCE = false,
                    )
                }
            }
        }
    }

    /**
     * Designated resolve (not synchronized)
     * @see runLazyDesignatedResolveWithoutLock for designated resolve
     * @see lazyResolveDeclaration synchronized version
     */
    private fun runLazyResolveWithoutLock(
        firDeclarationToResolve: FirDeclaration,
        moduleFileCache: ModuleFileCache,
        containerFirFile: FirFile,
        provider: FirProvider,
        scopeSession: ScopeSession,
        fromPhase: FirResolvePhase,
        toPhase: FirResolvePhase,
        checkPCE: Boolean,
    ) {
        if (fromPhase >= toPhase) return
        val nonLazyPhase = minOf(toPhase, LAST_NON_LAZY_PHASE)

        if (fromPhase < nonLazyPhase) {
            firFileBuilder.runResolveWithoutLock(
                firFile = containerFirFile,
                fromPhase = fromPhase,
                toPhase = nonLazyPhase,
                scopeSession = scopeSession,
                checkPCE = checkPCE
            )
        }
        if (toPhase <= nonLazyPhase) return

        runLazyDesignatedResolveWithoutLock(
            firDeclarationToResolve = firDeclarationToResolve,
            moduleFileCache = moduleFileCache,
            containerFirFile = containerFirFile,
            provider = provider,
            scopeSession = scopeSession,
            fromPhase = maxOf(LAST_NON_LAZY_PHASE, fromPhase),
            toPhase = toPhase,
            checkPCE = checkPCE,
            isOnAirResolve = false,
        )
    }

    /**
     * Run designated resolve only designation with fully resolved path (synchronized).
     * Suitable for body resolve or/and on-air resolve.
     * @see lazyResolveDeclaration for ordinary resolve
     * @param firDeclarationToResolve target non-local declaration
     * @param isOnAirResolve should be true when node does not belong to it's true designation (OnAir resolve in custom context)
     */
    fun lazyDesignatedResolveDeclaration(
        firDeclarationToResolve: FirDeclaration,
        moduleFileCache: ModuleFileCache,
        containerFirFile: FirFile,
        scopeSession: ScopeSession = ScopeSession(),
        toPhase: FirResolvePhase,
        checkPCE: Boolean,
        isOnAirResolve: Boolean,
        towerDataContextCollector: FirTowerDataContextCollector? = null,
    ) {
        if (toPhase == FirResolvePhase.RAW_FIR) return
        if (!firDeclarationToResolve.isAvailableForResolve()) return

        val provider = containerFirFile.moduleData.session.firIdeProvider
        val resolvableDeclaration = firDeclarationToResolve.getNonLocalDeclarationToResolve(provider, moduleFileCache)
        val designation = resolvableDeclaration.collectDesignation(containerFirFile)
        val resolvePhase = designation.resolvePhaseForAllDeclarations(isOnAirResolve)
        if (resolvePhase >= toPhase) return

        // Lazy since we want to read the resolve phase inside the lock. Otherwise, we may run the same resolve phase multiple times. See
        // KT-45121
        val fromPhase: FirResolvePhase by lazy(LazyThreadSafetyMode.NONE) {
            designation.resolvePhaseForAllDeclarations(isOnAirResolve)
        }

        if (checkPCE) {
            FirFileBuilder.runCustomResolveWithPCECheck(containerFirFile, moduleFileCache) {
                runLazyDesignatedResolveWithoutLock(
                    firDeclarationToResolve = firDeclarationToResolve,
                    moduleFileCache = moduleFileCache,
                    containerFirFile = containerFirFile,
                    provider = provider,
                    scopeSession = scopeSession,
                    fromPhase = fromPhase,
                    toPhase = toPhase,
                    checkPCE = checkPCE,
                    isOnAirResolve = isOnAirResolve,
                    towerDataContextCollector = towerDataContextCollector,
                )
            }
        } else {
            FirFileBuilder.runCustomResolveUnderLock(containerFirFile, moduleFileCache) {
                runLazyDesignatedResolveWithoutLock(
                    firDeclarationToResolve = firDeclarationToResolve,
                    moduleFileCache = moduleFileCache,
                    containerFirFile = containerFirFile,
                    provider = provider,
                    scopeSession = scopeSession,
                    fromPhase = fromPhase,
                    toPhase = toPhase,
                    checkPCE = checkPCE,
                    isOnAirResolve = isOnAirResolve,
                    towerDataContextCollector = towerDataContextCollector,
                )
            }
        }
    }

    /**
     * Designated resolve (not synchronized)
     * @see runLazyResolveWithoutLock for ordinary resolve
     * @see lazyDesignatedResolveDeclaration synchronized version
     */
    private fun runLazyDesignatedResolveWithoutLock(
        firDeclarationToResolve: FirDeclaration,
        moduleFileCache: ModuleFileCache,
        containerFirFile: FirFile,
        provider: FirProvider,
        scopeSession: ScopeSession,
        fromPhase: FirResolvePhase,
        toPhase: FirResolvePhase,
        checkPCE: Boolean,
        isOnAirResolve: Boolean,
        towerDataContextCollector: FirTowerDataContextCollector? = null,
    ) {
        var currentPhase = fromPhase
        runLazyResolveWithoutLock(
            firDeclarationToResolve = firDeclarationToResolve,
            moduleFileCache = moduleFileCache,
            containerFirFile = containerFirFile,
            provider = provider,
            scopeSession = scopeSession,
            fromPhase = currentPhase,
            toPhase = FirResolvePhase.IMPORTS,
            checkPCE = checkPCE,
        )
        currentPhase = maxOf(fromPhase, FirResolvePhase.IMPORTS)
        if (currentPhase >= toPhase) return

        if (firDeclarationToResolve is FirFile) {
            resolveFileAnnotationsWithoutLock(containerFirFile, containerFirFile.annotations, ScopeSession())
            return
        }

        val nonLocalDeclarationToResolve = firDeclarationToResolve.getNonLocalDeclarationToResolve(provider, moduleFileCache)
        val designation = nonLocalDeclarationToResolve.collectDesignation(containerFirFile)
        check(!designation.isLocalDesignation) { "Could not resolve local designation" }

        //This needed to override standard symbol resolve in supertype transformer with adding on-air created symbols
        val firProviderInterceptor = isOnAirResolve.ifTrue {
            FirProviderInterceptorForIDE.createForFirElement(
                session = designation.firFile.moduleData.session,
                firFile = designation.firFile,
                element = designation.declaration
            )
        }

        while (currentPhase < toPhase) {
            currentPhase = currentPhase.next
            if (currentPhase.pluginPhase) continue
            if (checkPCE) checkCanceled()
            FirLazyBodiesCalculator.calculateLazyBodiesInsideIfNeeded(designation, currentPhase)

            runLazyResolvePhase(
                phase = currentPhase,
                scopeSession = scopeSession,
                isOnAirResolve = isOnAirResolve,
                moduleFileCache = moduleFileCache,
                designation = designation,
                towerDataContextCollector = towerDataContextCollector,
                firProviderInterceptor = firProviderInterceptor,
                checkPCE = checkPCE,
            )
        }
    }

    private fun runLazyResolvePhase(
        phase: FirResolvePhase,
        scopeSession: ScopeSession,
        isOnAirResolve: Boolean,
        moduleFileCache: ModuleFileCache,
        designation: FirDeclarationUntypedDesignationWithFile,
        towerDataContextCollector: FirTowerDataContextCollector?,
        firProviderInterceptor: FirProviderInterceptor?,
        checkPCE: Boolean,
    ) {
        val transformer = phase.createLazyTransformer(
            designation,
            scopeSession,
            isOnAirResolve,
            moduleFileCache,
            towerDataContextCollector,
            firProviderInterceptor,
            checkPCE,
        )

        firFileBuilder.firPhaseRunner.runPhaseWithCustomResolve(phase) {
            transformer.transformDeclaration()
        }
    }

    private fun FirResolvePhase.createLazyTransformer(
        designation: FirDeclarationUntypedDesignationWithFile,
        scopeSession: ScopeSession,
        isOnAirResolve: Boolean,
        moduleFileCache: ModuleFileCache,
        towerDataContextCollector: FirTowerDataContextCollector?,
        firProviderInterceptor: FirProviderInterceptor?,
        checkPCE: Boolean,
    ): FirLazyTransformerForIDE = when (this) {
        FirResolvePhase.SUPER_TYPES -> FirDesignatedSupertypeResolverTransformerForIDE(
            designation = designation,
            session = designation.firFile.moduleData.session,
            scopeSession = scopeSession,
            isOnAirResolve = isOnAirResolve,
            moduleFileCache = moduleFileCache,
            firLazyDeclarationResolver = this@FirLazyDeclarationResolver,
            firProviderInterceptor = firProviderInterceptor,
            checkPCE = checkPCE,
        )
        FirResolvePhase.SEALED_CLASS_INHERITORS -> FirLazyTransformerForIDE.DUMMY
        FirResolvePhase.TYPES -> FirDesignatedTypeResolverTransformerForIDE(
            designation,
            designation.firFile.moduleData.session,
            scopeSession,
            isOnAirResolve,
        )
        FirResolvePhase.STATUS -> FirDesignatedStatusResolveTransformerForIDE(
            designation,
            designation.firFile.moduleData.session,
            scopeSession,
            isOnAirResolve,
        )
        FirResolvePhase.CONTRACTS -> FirDesignatedContractsResolveTransformerForIDE(
            designation,
            designation.firFile.moduleData.session,
            scopeSession,
            isOnAirResolve,
        )
        FirResolvePhase.IMPLICIT_TYPES_BODY_RESOLVE -> FirDesignatedImplicitTypesTransformerForIDE(
            designation,
            designation.firFile.moduleData.session,
            scopeSession,
            isOnAirResolve,
            towerDataContextCollector
        )
        FirResolvePhase.BODY_RESOLVE -> FirDesignatedBodyResolveTransformerForIDE(
            designation,
            designation.firFile.moduleData.session,
            scopeSession,
            isOnAirResolve,
            towerDataContextCollector,
            firProviderInterceptor,
        )
        else -> error("Non-lazy phase $this")
    }

    private fun FirDeclaration.getNonLocalDeclarationToResolve(provider: FirProvider, moduleFileCache: ModuleFileCache): FirDeclaration {
        //return getResolvableDeclaration(this, moduleFileCache)
        if (this is FirFile) return this

        if (this is FirPropertyAccessor || this is FirTypeParameter || this is FirValueParameter) {
            val ktContainingResolvableDeclaration = when (val psi = this.psi) {
                is KtPropertyAccessor -> psi.property
                is KtProperty -> psi
                is KtParameter, is KtTypeParameter -> psi.getNonLocalContainingOrThisDeclaration()
                    ?: error("Cannot find containing declaration for KtParameter")
                is KtCallExpression -> {
                    check(this.source?.kind == FirFakeSourceElementKind.DefaultAccessor)
                    val delegationCall = psi.parent as KtPropertyDelegate
                    delegationCall.parent as KtProperty
                }
                null -> error("Cannot find containing declaration for KtParameter")
                else -> error("Invalid source of property accessor ${psi::class}")
            }

            val targetElement =
                if (declarationCanBeLazilyResolved(ktContainingResolvableDeclaration)) ktContainingResolvableDeclaration
                else ktContainingResolvableDeclaration.getNonLocalContainingOrThisDeclaration()
            check(targetElement != null) { "Container for local declaration cannot be null" }

            return targetElement.findSourceNonLocalFirDeclaration(
                firFileBuilder = firFileBuilder,
                firSymbolProvider = moduleData.session.symbolProvider,
                moduleFileCache = moduleFileCache
            )
        }

        val ktDeclaration = (psi as? KtDeclaration) ?: run {
            (source as? FirFakeSourceElement<*>).psi?.let {
                PsiTreeUtil.getParentOfType(it, KtDeclaration::class.java)
            }
        }
        check(ktDeclaration is KtDeclaration) {
            "FirDeclaration should have a PSI of type KtDeclaration"
        }

        if (source !is FirFakeSourceElement<*> && declarationCanBeLazilyResolved(ktDeclaration)) return this
        val nonLocalPsi = ktDeclaration.getNonLocalContainingOrThisDeclaration()
            ?: error("Container for local declaration cannot be null")
        return nonLocalPsi.findSourceNonLocalFirDeclaration(firFileBuilder, provider.symbolProvider, moduleFileCache)
    }

    companion object {
        private val LAST_NON_LAZY_PHASE = FirResolvePhase.IMPORTS

        fun declarationCanBeLazilyResolved(declaration: KtDeclaration): Boolean {
            return when (declaration) {
                !is KtNamedDeclaration -> false
                is KtDestructuringDeclarationEntry, is KtFunctionLiteral, is KtTypeParameter -> false
                is KtPrimaryConstructor -> false
                is KtParameter -> {
                    if (declaration.hasValOrVar()) declaration.containingClassOrObject?.getClassId() != null
                    else false
                }
                is KtCallableDeclaration, is KtEnumEntry -> {
                    when (val parent = declaration.parent) {
                        is KtFile -> true
                        is KtClassBody -> (parent.parent as? KtClassOrObject)?.getClassId() != null
                        else -> false
                    }
                }
                is KtClassLikeDeclaration -> declaration.getClassId() != null
                else -> error("Unexpected ${declaration::class.qualifiedName}")
            }
        }
    }
}
