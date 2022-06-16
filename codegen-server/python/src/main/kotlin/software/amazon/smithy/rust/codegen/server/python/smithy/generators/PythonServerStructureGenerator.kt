/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.python.smithy.generators

import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.traits.ErrorTrait
import software.amazon.smithy.rust.codegen.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.rustlang.Writable
import software.amazon.smithy.rust.codegen.rustlang.render
import software.amazon.smithy.rust.codegen.rustlang.rust
import software.amazon.smithy.rust.codegen.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.rustlang.writable
import software.amazon.smithy.rust.codegen.smithy.RustSymbolProvider
import software.amazon.smithy.rust.codegen.smithy.generators.StructureGenerator
import software.amazon.smithy.rust.codegen.smithy.rustType
import software.amazon.smithy.rust.codegen.util.hasTrait

/**
 * To share structures defined in Rust with Python, `pyo3` provides the `PyClass` trait.
 * This class generates input / output / error structures definitions and implements the
 * `PyClass` trait.
 */
open class PythonServerStructureGenerator(
    model: Model,
    private val symbolProvider: RustSymbolProvider,
    private val writer: RustWriter,
    private val shape: StructureShape
) : StructureGenerator(model, symbolProvider, writer, shape) {

    override fun renderStructure() {
        writer.renderPyClass(shape)
        super.renderStructure()
        renderPyO3Methods()
    }

    override fun renderStructureMember(writer: RustWriter, member: MemberShape, memberName: String, memberSymbol: Symbol) {
        writer.renderPyGetterSetter()
        super.renderStructureMember(writer, member, memberName, memberSymbol)
    }

    private fun renderPyO3Methods() {
        if (shape.hasTrait<ErrorTrait>() || accessorMembers.isNotEmpty()) {
            writer.renderPyMethods()
            writer.rustTemplate(
                """
                impl $name {
                    ##[new]
                    pub fn new(#{bodysignature:W}) -> Self {
                        Self {
                            #{bodymembers:W}
                        }
                    }
                    fn __repr__(&self) -> String  {
                        format!("{self:?}")
                    }
                    fn __str__(&self) -> String {
                        format!("{self:?}")
                    }
                }
                """,
                "bodysignature" to renderStructSignatureMembers(),
                "bodymembers" to renderStructBodyMembers()
            )
        }
    }

    private fun renderStructSignatureMembers(): Writable =
        writable {
            forEachMember(members) { _, memberName, memberSymbol ->
                val memberType = memberSymbol.rustType()
                rust("$memberName: ${memberType.render()},")
            }
        }

    private fun renderStructBodyMembers(): Writable =
        writable {
            forEachMember(members) { _, memberName, _ -> rust("$memberName,") }
        }
}