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

package org.jacodb.panda.dynamic.ark.dto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.InputStream

@Serializable
data class ArkFileDto(
    val name: String,
    val absoluteFilePath: String,
    val projectDir: String,
    val projectName: String,
    val namespaces: List<NamespaceDto>,
    val classes: List<ClassDto>,
    val importInfos: List<ImportInfoDto>,
    val exportInfos: List<ExportInfoDto>,
) {
    companion object {
        private val json = Json {
            // classDiscriminator = "_"
            prettyPrint = true
        }

        fun loadFromJson(jsonString: String): ArkFileDto {
            return json.decodeFromString(jsonString)
        }

        @OptIn(ExperimentalSerializationApi::class)
        fun loadFromJson(stream: InputStream): ArkFileDto {
            return json.decodeFromStream(stream)
        }
    }
}

@Serializable
data class NamespaceDto(
    val name: String,
    val classes: List<ClassDto>,
)

@Serializable
data class ClassDto(
    val name: String,
    val modifiers: List<String>,
    val typeParameters: List<String>,
    val superClassName: String?,
    val implementedInterfaceNames: List<String>,
    val fields: List<FieldDto>,
    val methods: List<MethodDto>,
)

@Serializable
data class FieldDto(
    val name: String,
    val modifiers: List<String>,
    val type: String?,
    val questionToken: Boolean,
    val initializer: ValueDto?,
)

@Serializable
data class MethodDto(
    val name: String,
    val modifiers: List<String>,
    val typeParameters: List<String>,
    val parameters: List<ParameterDto>,
    val returnType: String,
    val body: List<StmtDto>,
)

@Serializable
data class ParameterDto(
    val name: String,
    val type: String,
    val isOptional: Boolean,
)

@Serializable
data class ImportInfoDto(
    val importClauseName: String,
    val importType: String,
    val importFrom: String,
    val nameBeforeAs: String?,
    val clauseType: String,
    val modifiers: List<String>,
    val importFromSignature: FileSignatureDto,
    val importProjectType: String,
    val originTsPosition: LineColPositionDto,
)

@Serializable
data class ExportInfoDto(
    val exportClauseName: String,
    val exportClauseType: String,
    val exportFrom: String? = null,
    val nameBeforeAs: String? = null,
    val declaringSignature: String? = null,
    val isDefault: Boolean,
    val importInfo: ImportInfoDto? = null,
    val modifiers: List<String>,
    val originTsPosition: LineColPositionDto,
)

// @Serializable
// data class Decorator(
//     val kind: String,
//     val type: String?,
// )

@Serializable
data class FileSignatureDto(
    val projectName: String,
    val fileName: String,
)

@Serializable
data class LineColPositionDto(
    val line: Int,
    val col: Int,
)
