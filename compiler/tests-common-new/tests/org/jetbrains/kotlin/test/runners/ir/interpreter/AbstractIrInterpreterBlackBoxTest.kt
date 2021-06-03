/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test.runners.ir.interpreter

import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.interpreter.IrInterpreter
import org.jetbrains.kotlin.ir.interpreter.compileTimeAnnotation
import org.jetbrains.kotlin.ir.interpreter.contractsDslAnnotation
import org.jetbrains.kotlin.ir.interpreter.evaluateIntrinsicAnnotation
import org.jetbrains.kotlin.ir.interpreter.hasAnnotation
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.resolve.AnalyzingUtils
import org.jetbrains.kotlin.test.Constructor
import org.jetbrains.kotlin.test.TargetBackend
import org.jetbrains.kotlin.test.backend.handlers.AbstractIrHandler
import org.jetbrains.kotlin.test.backend.ir.IrBackendInput
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.AdditionalFilesDirectives
import org.jetbrains.kotlin.test.directives.CodegenTestDirectives
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives
import org.jetbrains.kotlin.test.directives.LanguageSettingsDirectives
import org.jetbrains.kotlin.test.directives.model.DirectivesContainer
import org.jetbrains.kotlin.test.frontend.classic.ClassicFrontend2IrConverter
import org.jetbrains.kotlin.test.frontend.classic.ClassicFrontendFacade
import org.jetbrains.kotlin.test.frontend.classic.ClassicFrontendOutputArtifact
import org.jetbrains.kotlin.test.frontend.classic.handlers.ClassicFrontendAnalysisHandler
import org.jetbrains.kotlin.test.model.*
import org.jetbrains.kotlin.test.runners.AbstractKotlinCompilerWithTargetBackendTest
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.compilerConfigurationProvider
import org.jetbrains.kotlin.test.services.configuration.CommonEnvironmentConfigurator
import org.jetbrains.kotlin.test.services.configuration.JvmEnvironmentConfigurator
import org.jetbrains.kotlin.test.services.moduleStructure
import org.jetbrains.kotlin.test.services.sourceProviders.AdditionalDiagnosticsSourceFilesProvider
import org.jetbrains.kotlin.test.services.sourceProviders.CodegenHelpersSourceFilesProvider
import org.jetbrains.kotlin.test.services.sourceProviders.CoroutineHelpersSourceFilesProvider
import org.junit.jupiter.api.Assumptions

open class AbstractIrInterpreterBlackBoxTest : AbstractKotlinCompilerWithTargetBackendTest(TargetBackend.JVM_IR) {
    open val handler: Constructor<BackendInputHandler<*>>
        get() = { IrInterpreterBoxHandler(it) }

    override fun TestConfigurationBuilder.configuration() {
        globalDefaults {
            frontend = FrontendKinds.ClassicFrontend
            targetPlatform = JvmPlatforms.defaultJvmPlatform
            artifactKind = BinaryKind.NoArtifact
            targetBackend = TargetBackend.JVM_IR
            dependencyKind = DependencyKind.Source
        }

        defaultDirectives {
            +JvmEnvironmentConfigurationDirectives.FULL_JDK
            +JvmEnvironmentConfigurationDirectives.WITH_STDLIB
            LanguageSettingsDirectives.LANGUAGE with "-CompileTimeCalculations"
        }

        useConfigurators(
            ::CommonEnvironmentConfigurator,
            ::JvmEnvironmentConfigurator,
            ::IrInterpreterEnvironmentConfigurator
        )

        useAdditionalSourceProviders(
            ::AdditionalDiagnosticsSourceFilesProvider,
            ::CoroutineHelpersSourceFilesProvider,
            ::CodegenHelpersSourceFilesProvider,
        )

        useFrontendFacades(::ClassicFrontendFacade)
        useFrontendHandlers(::IrInterpreterNoCompilationErrorsHandler)
        useFrontend2BackendConverters(::ClassicFrontend2IrConverter)
        useBackendHandlers(handler)
    }
}

open class IrInterpreterBoxHandler(testServices: TestServices) : AbstractIrHandler(testServices) {
    private lateinit var irBuiltins: IrBuiltIns
    private val irFiles = mutableListOf<IrFile>()

    override fun processModule(module: TestModule, info: IrBackendInput) {
        irBuiltins = info.backendInput.irModuleFragment.irBuiltins
        irFiles.addAll(info.backendInput.irModuleFragment.files)
    }

    open fun additionalIgnores(modules: List<TestModule>) {
        Assumptions.assumeFalse(JvmEnvironmentConfigurationDirectives.WITH_REFLECT in testServices.moduleStructure.allDirectives) { "Ignore jvm reflection" }
    }

    override fun processAfterAllModules(someAssertionWasFailed: Boolean) {
        if (someAssertionWasFailed) return
        val modules = testServices.moduleStructure.modules

        Assumptions.assumeFalse(modules.flatMap { it.files }.any { it.name.endsWith(".java") }) { "Can't interpret java files" }
        Assumptions.assumeFalse(modules.flatMap { it.files }.singleOrNull()?.name == "sync.kt") { "Ignore `await` method call interpretation" }
        Assumptions.assumeFalse(AdditionalFilesDirectives.WITH_COROUTINES in testServices.moduleStructure.allDirectives) { "Ignore coroutines" }
        //Assumptions.assumeFalse(TargetBackend.JVM_IR in testServices.moduleStructure.allDirectives[CodegenTestDirectives.IGNORE_BACKEND]) { "Ignore test because of jvm ir ignore" }
        additionalIgnores(modules)

        val configuration = testServices.compilerConfigurationProvider.getCompilerConfiguration(modules.last())
        val boxFunction = irFiles
            .flatMap { it.declarations }
            .filterIsInstance<IrFunction>()
            .first { it.name.asString() == "box" && it.valueParameters.isEmpty() }

        val boxIrCall = IrCallImpl(
            UNDEFINED_OFFSET, UNDEFINED_OFFSET, irBuiltins.stringType, boxFunction.symbol as IrSimpleFunctionSymbol, 0, 0
        )

        val delegationChecker = object : IrElementVisitorVoid {
            var canBeEvaluated = true

            override fun visitElement(element: IrElement) {
                element.acceptChildren(this, null)
            }

            override fun visitFunction(declaration: IrFunction) {
                canBeEvaluated = canBeEvaluated && declaration.origin != IrDeclarationOrigin.DELEGATED_MEMBER
                canBeEvaluated = canBeEvaluated && declaration.origin != IrDeclarationOrigin.DELEGATED_PROPERTY_ACCESSOR
                super.visitFunction(declaration)
            }

            override fun visitProperty(declaration: IrProperty) {
                canBeEvaluated = canBeEvaluated && declaration.origin != IrDeclarationOrigin.DELEGATE
                super.visitProperty(declaration)
            }

            override fun visitField(declaration: IrField) {
                canBeEvaluated = canBeEvaluated && declaration.origin != IrDeclarationOrigin.DELEGATE
                super.visitField(declaration)
            }

            override fun visitLocalDelegatedProperty(declaration: IrLocalDelegatedProperty) {
                canBeEvaluated = false
            }
        }
        Assumptions.assumeTrue(irFiles.all { delegationChecker.apply { visitElement(it) }.canBeEvaluated }) { "Can't evaluate delegation" }

        val checker = object : IrElementVisitorVoid {
            var canBeEvaluated = true
            val visited = mutableListOf<IrElement>()

            fun IrDeclaration.isMarkedAsCompileTime() = isMarkedWith(compileTimeAnnotation)
            private fun IrDeclaration.isContract() = isMarkedWith(contractsDslAnnotation)
            private fun IrDeclaration.isMarkedAsEvaluateIntrinsic() = isMarkedWith(evaluateIntrinsicAnnotation)

            protected fun IrDeclaration.isMarkedWith(annotation: FqName): Boolean {
                if (this is IrClass && this.isCompanion) return false
                if (this.hasAnnotation(annotation)) return true
                return (this.parent as? IrClass)?.isMarkedWith(annotation) ?: false
            }

            override fun visitElement(element: IrElement) {
                element.acceptChildren(this, null)
            }

            override fun visitCall(expression: IrCall) {
                if (visited.contains(expression)) return else visited += expression

                if (expression.extensionReceiver is IrClassReference && expression.symbol.owner.name.asString() == "<get-java>") {
                    canBeEvaluated = false
                }

                val owner = expression.symbol.owner
                val name = owner.fqNameWhenAvailable?.asString() ?: ""
                canBeEvaluated = canBeEvaluated && !name.startsWith("java")
                canBeEvaluated = canBeEvaluated && !name.contains(".JvmClassMappingKt.")
                canBeEvaluated = canBeEvaluated && !(name.startsWith("kotlin") && name.endsWith(".clone"))
                if (!owner.isMarkedAsCompileTime() && !owner.isMarkedAsEvaluateIntrinsic()) owner.body?.accept(this, null)
                super.visitCall(expression)
            }

            override fun visitTypeOperator(expression: IrTypeOperatorCall) {
                canBeEvaluated = canBeEvaluated && expression.operator != IrTypeOperator.SAM_CONVERSION
                canBeEvaluated = canBeEvaluated && expression.operator != IrTypeOperator.IMPLICIT_DYNAMIC_CAST
                super.visitTypeOperator(expression)
            }

            override fun visitDynamicOperatorExpression(expression: IrDynamicOperatorExpression) {
                canBeEvaluated = false
            }
        }

        Assumptions.assumeTrue(checker.apply { visitCall(boxIrCall, null) }.canBeEvaluated) { "" }

        val interpreterResult = try {
            @Suppress("UNCHECKED_CAST")
            val irInterpreter = IrInterpreter(irBuiltins, configuration[CommonConfigurationKeys.IR_BODY_MAP] as Map<IdSignature, IrBody>)
            irInterpreter.interpret(boxIrCall, irFiles.last())
        } catch (e: Throwable) {
            val message = e.message
            if (message == "Cannot interpret get method on top level non const properties" || message == "Cannot interpret set method on top level properties") {
                Assumptions.assumeFalse(true) { message }
                return
            }
            throw e
        }

        if (interpreterResult is IrErrorExpression) assertions.fail { interpreterResult.description }
        if (interpreterResult !is IrConst<*>) assertions.fail { "Expect const, but returned ${interpreterResult::class.java}" }
        assertions.assertEquals("OK", interpreterResult.value)
    }
}

class IrInterpreterEnvironmentConfigurator(testServices: TestServices) : EnvironmentConfigurator(testServices) {
    private val fullRuntimeKlib = "build/js-ir-runtime/klib"

    override fun configureCompilerConfiguration(configuration: CompilerConfiguration, module: TestModule) {
        configuration.put(JVMConfigurationKeys.KLIB_PATH_FOR_COMPILE_TIME, fullRuntimeKlib)
    }
}

class IrInterpreterNoCompilationErrorsHandler(testServices: TestServices) : ClassicFrontendAnalysisHandler(
    testServices,
    failureDisablesNextSteps = true
) {
    override val directivesContainers: List<DirectivesContainer>
        get() = listOf(CodegenTestDirectives)

    override fun processModule(module: TestModule, info: ClassicFrontendOutputArtifact) {
        if (CodegenTestDirectives.IGNORE_ERRORS in module.directives) return
        try {
            AnalyzingUtils.throwExceptionOnErrors(info.analysisResult.bindingContext)
        } catch (e: Exception) {
            Assumptions.assumeFalse(true) { e.stackTraceToString() }
        }
    }

    override fun processAfterAllModules(someAssertionWasFailed: Boolean) {}
}