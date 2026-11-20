package app.carry.core.json

import kotlinx.serialization.json.Json

object CarryJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }
}
