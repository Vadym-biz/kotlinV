/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.codegen.optimization.common

import org.jetbrains.kotlin.codegen.inline.insnText
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.tree.*
import org.jetbrains.org.objectweb.asm.tree.analysis.AnalyzerException
import java.util.*

class InstructionLivenessAnalyzer(val method: MethodNode) {
    private val instructions: InsnList = method.instructions
    private val nInsns: Int = instructions.size()

    private val isLive = BooleanArray(nInsns) { false }

    private val handlers: Array<MutableList<TryCatchBlockNode>?> = arrayOfNulls(nInsns)
    private val queued: BooleanArray = BooleanArray(nInsns)
    private val queue: IntArray = IntArray(nInsns)
    private var top: Int = 0

    private val AbstractInsnNode.indexOf get() = instructions.indexOf(this)

    fun analyze(): BooleanArray {
        if (nInsns == 0) return isLive
        checkAssertions()
        computeExceptionHandlersForEachInsn(method)
        initControlFlowAnalysis()
        traverseCfg()
        localVariableAndTryCatchBlockLabelsAreAlwaysLive()
        return isLive
    }

    private fun traverseCfg() {
        while (top > 0) {
            val insn = queue[--top]
            queued[insn] = false

            val insnNode = method.instructions[insn]
            try {
                val insnOpcode = insnNode.opcode
                val insnType = insnNode.type

                if (insnType == AbstractInsnNode.LABEL || insnType == AbstractInsnNode.LINE || insnType == AbstractInsnNode.FRAME) {
                    visitOpInsn(insn)
                } else {
                    when {
                        insnNode is JumpInsnNode ->
                            visitJumpInsnNode(insnNode, insn, insnOpcode)
                        insnNode is LookupSwitchInsnNode ->
                            visitLookupSwitchInsnNode(insnNode)
                        insnNode is TableSwitchInsnNode ->
                            visitTableSwitchInsnNode(insnNode)
                        insnOpcode != Opcodes.ATHROW && (insnOpcode < Opcodes.IRETURN || insnOpcode > Opcodes.RETURN) ->
                            visitOpInsn(insn)
                        else -> {
                        }
                    }
                }

                handlers[insn]?.forEach { tcb ->
                    mergeControlFlowEdge(tcb.handler.indexOf)
                }

            } catch (e: AnalyzerException) {
                throw AnalyzerException(e.node, "Error at instruction #$insn ${insnNode.insnText}: ${e.message}", e)
            } catch (e: Exception) {
                throw AnalyzerException(insnNode, "Error at instruction #$insn ${insnNode.insnText}: ${e.message}", e)
            }
        }
    }

    private fun localVariableAndTryCatchBlockLabelsAreAlwaysLive() {
        for (localVariable in method.localVariables) {
            isLive[localVariable.start.indexOf] = true
            isLive[localVariable.end.indexOf] = true
        }

        for (tcb in method.tryCatchBlocks) {
            isLive[tcb.start.indexOf] = true
            isLive[tcb.end.indexOf] = true
            isLive[tcb.handler.indexOf] = true
        }
    }

    private fun checkAssertions() {
        if (instructions.toArray().any { it.opcode == Opcodes.JSR || it.opcode == Opcodes.RET })
            throw AssertionError("Subroutines are deprecated since Java 6")
    }

    private fun visitOpInsn(insn: Int) {
        processControlFlowEdge(insn + 1)
    }

    private fun visitTableSwitchInsnNode(insnNode: TableSwitchInsnNode) {
        var jump = insnNode.dflt.indexOf
        processControlFlowEdge(jump)
        for (label in insnNode.labels.reversed()) {
            jump = instructions.indexOf(label)
            processControlFlowEdge(jump)
        }
    }

    private fun visitLookupSwitchInsnNode(insnNode: LookupSwitchInsnNode) {
        var jump = insnNode.dflt.indexOf
        processControlFlowEdge(jump)
        for (label in insnNode.labels) {
            jump = label.indexOf
            processControlFlowEdge(jump)
        }
    }

    private fun visitJumpInsnNode(insnNode: JumpInsnNode, insn: Int, insnOpcode: Int) {
        if (insnOpcode != Opcodes.GOTO && insnOpcode != Opcodes.JSR) {
            processControlFlowEdge(insn + 1)
        }
        val jump = insnNode.label.indexOf
        processControlFlowEdge(jump)
    }

    private fun processControlFlowEdge(jump: Int) {
        mergeControlFlowEdge(jump)
    }

    private fun initControlFlowAnalysis() {
        mergeControlFlowEdge(0)
    }

    private fun computeExceptionHandlersForEachInsn(m: MethodNode) {
        for (tcb in m.tryCatchBlocks) {
            val begin = instructions.indexOf(tcb.start)
            val end = instructions.indexOf(tcb.end)
            for (j in begin until end) {
                var insnHandlers = handlers[j]
                if (insnHandlers == null) {
                    insnHandlers = ArrayList<TryCatchBlockNode>()
                    handlers[j] = insnHandlers
                }
                insnHandlers.add(tcb)
            }
        }
    }

    private fun mergeControlFlowEdge(insn: Int) {
        val changes = !isLive[insn]
        isLive[insn] = true
        if (changes && !queued[insn]) {
            queued[insn] = true
            queue[top++] = insn
        }
    }

}
