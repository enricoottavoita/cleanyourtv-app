package tv.cleanyour.core.flows

import tv.cleanyour.core.spec.StyleId
import kotlin.test.Test
import kotlin.test.assertEquals

// 0.4.7: Customise said "Look: Default" for a look with the person's own photo; the name now follows the choice.
class LookNameTest {
    private fun draft(picture: String?, style: StyleId = StyleId.CLASSIC, legacy: String? = null) =
        LookDraft(themeId = "default", picture = picture, style = style, legacyThemeId = legacy)

    @Test fun theNameFollowsTheStyleAndThePicture() {
        assertEquals(LookName.Styled(StyleId.CLASSIC, photo = true), LookName.of(draft(LookPicture.MINE_ID), null))
        assertEquals(LookName.Styled(StyleId.GLASS, photo = true), LookName.of(draft(LookPicture.NEW_ID, StyleId.GLASS), null))
        assertEquals(LookName.Styled(StyleId.CLASSIC, photo = true), LookName.of(draft(LookPicture.EARLIER_ID), null))
        assertEquals(LookName.Styled(StyleId.CALM, picture = "mist"), LookName.of(draft("mist", StyleId.CALM), null))
        assertEquals(LookName.Styled(StyleId.SPOTLIGHT), LookName.of(draft(LookPicture.NONE_ID, StyleId.SPOTLIGHT), null))
        // The Default look stays "Default", and a 0.4.0 look keeps its name.
        assertEquals(LookName.Legacy(null), LookName.of(draft(LookPicture.NONE_ID), null))
        assertEquals(LookName.Legacy("aurora"), LookName.of(draft("mist"), "aurora"))
        assertEquals(LookName.Legacy("aurora"), LookName.of(draft("mist", legacy = "aurora"), null))
        assertEquals(LookName.Legacy("aurora"), LookName.of(null, "aurora"))
    }
}
