/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

package software.amazon.smithy.rust.codegen.smithy.generators

import software.amazon.smithy.codegen.core.SymbolProvider
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.traits.ErrorTrait
import software.amazon.smithy.rust.codegen.rustlang.RustType
import software.amazon.smithy.rust.codegen.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.rustlang.documentShape
import software.amazon.smithy.rust.codegen.rustlang.rustBlock
import software.amazon.smithy.rust.codegen.smithy.canUseDefault
import software.amazon.smithy.rust.codegen.smithy.expectRustMetadata
import software.amazon.smithy.rust.codegen.smithy.isOptional
import software.amazon.smithy.rust.codegen.smithy.rustType

fun RustWriter.implBlock(structureShape: Shape, symbolProvider: SymbolProvider, block: RustWriter.() -> Unit) {
    rustBlock("impl ${symbolProvider.toSymbol(structureShape).name}") {
        block(this)
    }
}

class StructureGenerator(
    val model: Model,
    private val symbolProvider: SymbolProvider,
    private val writer: RustWriter,
    private val shape: StructureShape
) {
    private val members: List<MemberShape> = shape.allMembers.values.toList()

    fun render() {
        renderStructure()
        val errorTrait = shape.getTrait(ErrorTrait::class.java)
        errorTrait.map {
            val errorGenerator = ErrorGenerator(model, symbolProvider, writer, shape, it)
            errorGenerator.render()
        }
    }

    companion object {
        fun fallibleBuilder(structureShape: StructureShape, symbolProvider: SymbolProvider): Boolean = structureShape
            .allMembers
            .values.map { symbolProvider.toSymbol(it) }.any {
                // If any members are not optional && we can't use a default, we need to
                // generate a fallible builder
                !it.isOptional() && !it.canUseDefault()
            }
    }

    /**
     * Search for lifetimes used by the members of the struct and generate a declaration.
     * eg. `<'a, 'b>`
     */
    private fun lifetimeDeclaration(): String {
        val lifetimes = members
            .map { symbolProvider.toSymbol(it).rustType() }
            .mapNotNull {
                when (it) {
                    is RustType.Reference -> it.lifetime
                    else -> null
                }
            }.toSet().sorted()
        return if (lifetimes.isNotEmpty()) {
            "<${lifetimes.joinToString { "'$it" }}>"
        } else ""
    }

    private fun renderStructure() {
        val symbol = symbolProvider.toSymbol(shape)
        val containerMeta = symbol.expectRustMetadata()
        writer.documentShape(shape, model)
        containerMeta.render(writer)

        writer.rustBlock("struct ${symbol.name} ${lifetimeDeclaration()}") {
            members.forEach { member ->
                val memberName = symbolProvider.toMemberName(member)
                writer.documentShape(member, model)
                symbolProvider.toSymbol(member).expectRustMetadata().render(this)
                write("$memberName: #T,", symbolProvider.toSymbol(member))
            }
        }
    }
}