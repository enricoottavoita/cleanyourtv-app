package tv.cleanyour.ui.design.component

import tv.cleanyour.core.flows.LookDraft
import tv.cleanyour.core.flows.PictureOption
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.spec.DockLayers

// The one mapping from the flows' Look choice (LookDrafts makes it for both machines) to what the
// Look editor shows, for the Look sheet after Setup and Setup's look step alike
// (RFC-LOOK-SETUP-2). No other file maps a LookDraft. A caller hands in only what its own state
// holds: the gallery, the person's photo kept for this TV and a photo just made here with their
// dims, and the earlier look's photo (the Look sheet only). Everything else, the rows of More
// options and Advanced, the style cards, the way back and the notes, the flows have resolved in
// the draft already.
fun LookDraft.toEditor(
    pictures: List<PictureOption>,
    ownPicture: BlobRef?,
    ownDim: Int,
    newPicture: BlobRef?,
    newDim: Int,
    earlierPicture: BlobRef? = null,
): LookEditorState = LookEditorState(
    style = style, picture = picture ?: LookEditorState.NONE, columns = columns, preview = preview,
    styles = styles, styleCards = styleCards, reads27 = reads27,
    pictures = pictures.map { LookEditorPicture(it.id, it.name, it.path, it.until, it.backgroundArgb) },
    minePath = ownPicture?.path, newPath = newPicture?.path, earlierPath = earlierPicture?.path,
    photoDim = if (newPicture != null) newDim else ownDim,
    apps = apps, waiting = waiting,
    minColumns = DockLayers.MIN_OFFERED_COLUMNS, maxColumns = DockLayers.MAX_OFFERED_COLUMNS,
    names = names, movement = movement, movementPicked = movementPicked, lift = lift, details = details, rows = rows,
    changed = changed, newPhoto = newPhoto, wayBack = wayBack, note = note, ramMb = ramMb, keyNames = keyNames,
    settingsFile = settingsFile, fullFile = fullFile, tvChanged = tvChanged, reads30 = reads30, extras = extras,
    actingExtras = actingExtras, offeredExtras = offeredExtras, canSend = canSend,
)
