package tv.cleanyour.ui.design.component

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tv.cleanyour.core.flows.LookEdit
import tv.cleanyour.core.flows.WayBack
import tv.cleanyour.ui.design.R

// The Look sheet's pinned footer (W3, W8): "Put it on the TV", the one guarded write of every page, only while the
// choice would change something (0.4.8)
// (a tap through another app's overlay is dropped, R4-W8), then at most one way back under it:
// "Undo changes" while the choice differs from what was sent, otherwise "Put back the earlier look"
// when there is one. Then, on the Look page and only after the TV's own settings were seen
// changed, the line that a send replaces them (in the list instead at large text). The way back
// only changes the phone's choice, so it is not guarded.
@Composable
fun ColumnScope.LookSendFooter(
    state: LookEditorState,
    page: LookPage,
    onPut: () -> Unit,
    onEdit: (LookEdit) -> Unit,
) {
    // 0.4.8 (beta1 QA): with nothing changed there is nothing to send; one line says the TV has it.
    if (state.canSend) PrimaryButton(stringResource(R.string.cyt_look_use), onPut, guardObscured = true)
    else LookBody(stringResource(R.string.cyt_look_on_tv), Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp, bottom = 4.dp))
    when (state.wayBack) {
        WayBack.UNDO -> TertiaryButton(stringResource(R.string.cyt_look_undo_changes), { onEdit(LookEdit.UndoChanges) },
            Modifier.align(Alignment.CenterHorizontally), plain = true)
        WayBack.EARLIER -> TertiaryButton(stringResource(R.string.cyt_look_earlier), { onEdit(LookEdit.PutBackEarlier) },
            Modifier.align(Alignment.CenterHorizontally), plain = true)
        WayBack.NONE -> {}
    }
    if (page == LookPage.MAIN && state.tvChanged && !LocalTvLineInList.current && LocalDensity.current.fontScale < TV_LINE_IN_LIST_FONT_SCALE) {
        LookBody(stringResource(R.string.cyt_look_tv_note), Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp))
    }
}

// Setup's look step: Done only. The choice goes to the TV when Setup runs, so Done just closes the
// step and is not guarded.
@Composable
fun ColumnScope.LookDoneFooter(onDone: () -> Unit) {
    PrimaryButton(stringResource(R.string.cyt_look_done), onDone, guardObscured = false)
}
