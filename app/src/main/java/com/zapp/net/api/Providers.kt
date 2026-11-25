package com.zapp.net.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Provider + model picker data, shapes verified against two live servers:
 *
 *   PC build    GET /api/model -> data[i] {id, modelID, providerID, name, variants[{id}]}
 *   phone 1.19.6 GET /api/model -> data[i] {id, providerID, name, api{id}, variants[]}
 *
 * The phone build drops `modelID` entirely and the registry key is just `id`, so the
 * field is defaulted and filled from `id` after decode. Getting this wrong surfaced as
 * `Picker load failed: Field 'modelID' is required for type with serial name` and an
 * empty model menu, which is why the parse is now version-tolerant instead of strict.
 */
@Serializable
data class Provider(
    val id: String,
    val name: String,
    val integrationID: String? = null,
    val activation: String? = null,
)

@Serializable
data class ModelVariant(val id: String)

/**
 * `variants` is an array of objects on one build and may be an array of plain strings on
 * another; both are accepted so a server upgrade can never break the picker again.
 */
object VariantList : KSerializer<List<ModelVariant>> {
    override val descriptor: SerialDescriptor =
        ListSerializer(ModelVariant.serializer()).descriptor

    override fun deserialize(decoder: Decoder): List<ModelVariant> {
        val input = decoder as? JsonDecoder ?: return emptyList()
        val el = input.decodeJsonElement()
        if (el !is JsonArray) return emptyList()
        return el.mapNotNull { e ->
            when (e) {
                is JsonPrimitive -> e.content.ifBlank { null }?.let { ModelVariant(it) }
                is JsonObject -> (e["id"] as? JsonPrimitive)?.content?.let { ModelVariant(it) }
                else -> null
            }
        }
    }

    override fun serialize(encoder: Encoder, value: List<ModelVariant>) =
        encoder.encodeSerializableValue(ListSerializer(ModelVariant.serializer()), value)
}

@Serializable
data class ModelApi(
    val id: String = "",
    val type: String = "",
    @SerialName("package") val pkg: String? = null,
    val url: String? = null,
)

/** `/api/model` -> `limit:{context, output}`. Desktop divides the turn's tokens by
    `limit.context` to drive the progress circle in the session header. */
@Serializable
data class ModelLimit(
    val context: Long = 0,
    val output: Long = 0,
)

/** One pricing row from `/api/model` -> `cost:[{input,output,cache:{read,write}}]`. */
@Serializable
data class ModelCost(
    val input: Double = 0.0,
    val output: Double = 0.0,
    val cache: ModelCacheCost? = null,
)

@Serializable
data class ModelCacheCost(
    val read: Double = 0.0,
    val write: Double = 0.0,
)

@Serializable
data class ModelInfo(
    val id: String = "",
    @SerialName("modelID") val modelIdField: String = "",
    val providerID: String = "",
    val name: String = "",
    val type: String? = null,
    /** "active" / "deprecated", sent by the server per model. Absent on older builds. */
    val status: String? = null,
    /** Wire descriptor. `type` is "native" on models an older client cannot call at all. */
    val api: ModelApi? = null,
    @Serializable(with = VariantList::class) val variants: List<ModelVariant> = emptyList(),
    /** `{context, output}` - desktop reads `limit.context` for the header usage ring. */
    val limit: ModelLimit? = null,
    /** Per-million pricing rows; all-zero means the model is free, which hides the cost. */
    val cost: List<ModelCost> = emptyList(),
) {
    /** Registry key used by prompt/select calls: `modelID` when sent, else `id`. */
    val modelID: String get() = modelIdField.ifBlank { id }

    /** False only when the server explicitly marks it retired. Missing status = assume live. */
    val live: Boolean get() = status == null || status.equals("active", ignoreCase = true)
}

@Serializable
private data class ListEnvelope<T>(val data: List<T>)

private val j = Json { ignoreUnknownKeys = true }

suspend fun OpenCodeClient.providers(directory: String? = null): List<Provider> {
    val q = if (directory != null)
        "?location[directory]=${java.net.URLEncoder.encode(directory, "UTF-8")}" else ""
    return j.decodeFromString<ListEnvelope<Provider>>(get("/api/provider$q")).data
}

suspend fun OpenCodeClient.models(directory: String? = null): List<ModelInfo> {
    val q = if (directory != null)
        "?location[directory]=${java.net.URLEncoder.encode(directory, "UTF-8")}" else ""
    return j.decodeFromString<ListEnvelope<ModelInfo>>(get("/api/model$q")).data
        .filter { it.modelID.isNotBlank() && it.providerID.isNotBlank() }
}

/** One provider with the models desktop would show under it. */
data class ProviderGroup(val provider: Provider, val models: List<ModelInfo>)

/**
 * Direct port of `runtime/server/global-sync/utils.ts` (`buildProviderCatalogs`):
 *
 *   for (const provider of providers) all.set(provider.id, ...)
 *   for (const model of models) {
 *     const provider = all.get(model.providerID)
 *     if (!provider || model.status === "deprecated") continue
 *     provider.models[model.id] = ...
 *   }
 *
 * Two rules, exactly theirs and nothing else:
 *  1. a model whose `providerID` is not in `GET /api/provider` is dropped;
 *  2. a model marked `deprecated` is dropped.
 * A model with no `status` at all is KEPT — the server only omits it on builds that
 * predate the flag, and desktop keeps those too.
 *
 * Provider order is the server's provider order; model order is the server's model
 * order. No sorting, no ranking, no personal allowlist.
 */
fun providerGroups(providers: List<Provider>, models: List<ModelInfo>): List<ProviderGroup> =
    providers.mapNotNull { p ->
        val own = models.filter { it.providerID == p.id && it.status != "deprecated" }
        if (own.isEmpty()) null else ProviderGroup(p, own)
    }

/** Their per-provider default: first non-deprecated model in server order. */
fun defaultModelFor(providers: List<Provider>, models: List<ModelInfo>, providerID: String): ModelInfo? =
    if (providers.none { it.id == providerID }) null
    else models.firstOrNull { it.providerID == providerID && it.status != "deprecated" }
