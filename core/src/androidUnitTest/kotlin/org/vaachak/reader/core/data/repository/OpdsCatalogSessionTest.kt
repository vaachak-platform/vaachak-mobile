package org.vaachak.reader.core.data.repository

import org.junit.Test
import org.vaachak.reader.core.domain.model.OpdsEntity

/** Identity stamp contains no exposed credential values; lifecycle tests cover secure resolution. */
class OpdsCatalogSessionTest {
    private val row = OpdsEntity(id = 1, title = "Fixture", url = "https://catalog.example/opds",
        username = "fixture-user", password = "fixture-password")

    @Test fun duplicateUrlsCannotMatchAnotherRow() {
        val session = OpdsCatalogSession(row)
        check(session.matches(row) && !session.matches(row.copy(id = 2)))
    }
    @Test fun urlChangesInvalidateSelectionEvenOnSameOrigin() {
        val session = OpdsCatalogSession(row)
        check(!session.matches(row.copy(url = row.url + "/folder")))
    }
    @Test fun changedLegacyCredentialCannotMatchOldSelection() {
        val session = OpdsCatalogSession(row)
        check(!session.matches(row.copy(password = "changed")))
        check(!session.matches(row.copy(username = "changed")))
    }
    @Test fun metadataOnlyEditRetainsBrowserButInvalidatesEditor() {
        val session = OpdsCatalogSession(row)
        val renamed = row.copy(title = "Renamed")
        check(session.matches(renamed) && !session.matchesEditor(renamed))
        check(!session.matchesEditor(row.copy(isPredefined = true)))
        check(session.matchesEditor(row))
    }
    @Test fun anonymousNullAndEmptyAreDistinct() {
        val public = row.copy(username = null, password = null)
        val session = OpdsCatalogSession(public)
        check(session.matches(public) && !session.matches(public.copy(password = "")))
        check(!session.matches(public.copy(username = "")))
    }
    @Test fun encryptedGenerationChangeInvalidatesSession() {
        val active = row.copy(username = null, password = null, credentialGeneration = OpdsCredentialRef.create(row.id).generation)
        val session = OpdsCatalogSession(active)
        check(session.matches(active))
        check(!session.matches(active.copy(credentialGeneration = OpdsCredentialRef.create(row.id).generation)))
        check(!session.matches(active.copy(credentialGeneration = null)))
    }
    @Test fun invalidIdentityNeverMatches() {
        val invalid = row.copy(id = 0)
        check(!OpdsCatalogSession(invalid).matches(invalid))
    }
    @Test fun sessionAndEntityAreRedactedAndReopeningCreatesNewIdentity() {
        val a = OpdsCatalogSession(row)
        val b = OpdsCatalogSession(row)
        check(a !== b && a != b && a.toString() == "OpdsCatalogSession(REDACTED)")
        check(row.toString() == "OpdsEntity(REDACTED)")
    }
}
