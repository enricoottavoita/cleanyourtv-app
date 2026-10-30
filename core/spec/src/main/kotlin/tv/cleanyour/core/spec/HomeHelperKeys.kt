package tv.cleanyour.core.spec

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import tv.cleanyour.core.model.PackageName

// RFC-HELPER-1 (test build, cyt.homeHelper): the two config.json keys of the Home button helper, the
// only ones this app writes for it. stock_home_redirect names the TV's own home screen, which the
// launcher's HomeKeyService sends on to the new one whenever it comes to the front; key_filter false
// keeps the helper from filtering keys, so held Volume keeps repeating and no button press reaches
// it (PLAN 4.3). Both are carried keys (ConfigCodecImpl.CARRIED_READ): the doc has no field for
// them, so they live in `unknown` and the codec writes them after the modelled keys.
//
// Nothing else of the launcher's key helpers is ever written: not restore_key_services,
// power_on_home, home_long_press_home, consume_volume_keys, gamepad_passthrough, handle_view_inputs,
// settings_key_panel, app_keys or a front: entry.
object HomeHelperKeys {
    const val REDIRECT = "stock_home_redirect"
    const val KEY_FILTER = "key_filter"

    // The doc with the helper's keys for this TV's own home screen, which must be one of Google's.
    fun with(doc: LauncherConfigDoc, stock: PackageName): LauncherConfigDoc {
        require(KnownLaunchers.generic.any { it.pkg == stock }) { "the helper only ever sends on from Google's TV home screens" }
        return doc.copy(unknown = LinkedHashMap(doc.unknown).apply {
            put(REDIRECT, JsonPrimitive(stock.value))
            put(KEY_FILTER, JsonPrimitive(false))
        })
    }

    // The redirect a config.json names (trimmed, as Config.java reads it), or null when it names none.
    fun redirectOf(overlay: ByteArray?): PackageName? {
        val doc = overlay?.let { (LauncherConfigCodec.decode(it) as? DecodeResult.Ok)?.doc } ?: return null
        return redirectOf(doc)
    }

    fun redirectOf(doc: LauncherConfigDoc): PackageName? {
        val v = doc.unknown[REDIRECT]?.let { runCatching { it.jsonPrimitive }.getOrNull() }?.takeIf { it.isString }?.content?.trim().orEmpty()
        return v.takeIf { it.isNotEmpty() }?.let { runCatching { PackageName(it) }.getOrNull() }
    }

    fun keyFilterOf(doc: LauncherConfigDoc): Boolean? = doc.unknown[KEY_FILTER]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() }

    // A look pushed while the helper is on keeps it working: the redirect and key_filter of the TV's
    // overlay go into the new doc, unless it sets them itself. Only when `helperListed` (our
    // HomeKeyService is in the TV's accessibility list) and only a Google home screen's redirect: a
    // switched off helper's keys go with the next look, and a hostile overlay's redirect is not kept.
    fun carry(doc: LauncherConfigDoc, overlay: ByteArray?, helperListed: Boolean): LauncherConfigDoc {
        if (!helperListed || REDIRECT in doc.unknown) return doc
        val stock = redirectOf(overlay)?.takeIf { s -> KnownLaunchers.generic.any { it.pkg == s } } ?: return doc
        return with(doc, stock)
    }
}
