package com.trippulse.app

import com.trippulse.app.ui.FollowLinkInbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The follow link carries the credential in the URL fragment. These pin the
 * two shapes it may take and that anything else is safely ignored.
 */
class FollowLinkTest {

    @Test fun code_dash_passcode() {
        val p = FollowLinkInbox.parseFragment("40381927-123456")!!
        assertEquals("40381927", p.code)
        assertEquals("123456", p.passcode)
    }

    @Test fun hash_prefixed_query_form() {
        val p = FollowLinkInbox.parseFragment("#j=40381927&p=123456")!!
        assertEquals("40381927", p.code)
        assertEquals("123456", p.passcode)
    }

    @Test fun number_only_link_leaves_passcode_blank() {
        val p = FollowLinkInbox.parseFragment("40381927-")!!
        assertEquals("40381927", p.code)
        assertEquals("", p.passcode)
    }

    @Test fun non_follow_fragments_are_ignored() {
        assertNull(FollowLinkInbox.parseFragment(null))
        assertNull(FollowLinkInbox.parseFragment(""))
        assertNull(FollowLinkInbox.parseFragment("section-heading-only"))
    }
}
