package ai.trustshield.android.input.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3-07 unit tests - the pure decision core of [ShareIntentReaderCore] plus the flag mapping.
 *
 * Nothing here constructs an `Intent`, `Uri`, `Context` or `ContentResolver`: android.jar method
 * bodies are stubs that throw at runtime. The adapter ([ShareIntentReader]) is compile-verified by
 * `:app:assembleDebug` and documented as untested off-device; every decision it makes is delegated
 * to the pure code exercised below.
 *
 * No test prints a payload. All assertions are on shapes, enum codes and lengths.
 */
class ShareIntentReaderTest {

    // ------------------------------------------------------------------ fixtures

    private fun snapshot(
        action: String? = ShareActions.ACTION_SEND,
        declaredMime: String? = ShareActions.MIME_TEXT_PLAIN,
        text: String? = null,
        subject: String? = null,
        title: String? = null,
        htmlText: String? = null,
        clipItemCount: Int = 0,
        clipTextParts: List<String> = emptyList(),
        clipUris: List<ShareIntentStreamSnapshot> = emptyList(),
        extraStreamUris: List<ShareIntentStreamSnapshot> = emptyList(),
        dataUri: String? = null,
        readGrant: ShareUriGrant = ShareUriGrant.TransientRead,
    ): ShareIntentSnapshot = ShareIntentSnapshot(
        action = action,
        declaredMime = declaredMime,
        text = text,
        subject = subject,
        title = title,
        htmlText = htmlText,
        clipItemCount = clipItemCount,
        clipTextParts = clipTextParts,
        clipUris = clipUris,
        extraStreamUris = extraStreamUris,
        dataUri = dataUri,
        readGrant = readGrant,
    )

    private fun contentStream(
        path: String,
        mime: String?,
        providerMime: String? = null,
        declaredBytes: Long? = null,
    ): ShareIntentStreamSnapshot = ShareIntentStreamSnapshot(
        uriString = "content://com.example.provider/document/$path",
        scheme = "content",
        declaredMime = mime,
        declaredBytes = declaredBytes,
        providerMime = providerMime,
    )

    private fun accepted(read: ShareIntentRead): ShareIntentRead.Accepted {
        assertTrue("expected Accepted, got $read", read is ShareIntentRead.Accepted)
        return read as ShareIntentRead.Accepted
    }

    private fun refused(read: ShareIntentRead): ShareIntentRead.Refused {
        assertTrue("expected Refused, got $read", read is ShareIntentRead.Refused)
        return read as ShareIntentRead.Refused
    }

    private fun SharedContent.partKinds(): List<SharePartKind> = parts.map { it.kind }

    private fun ShareIntentRead.issueCodes(): List<String> = issues.map { it.code }

    // ------------------------------------------------------------------ text intake

    @Test
    fun sendTextBodyOnlyIsAcceptedAsAnalysableText() {
        val read = ShareIntentReaderCore.read(
            snapshot(text = "Sir aapka account block ho jayega, turant verify karein"),
        )
        val result = accepted(read)
        val content = result.share.content
        assertTrue("expected Text, got $content", content is SharedContent.Text)
        assertEquals(
            "Sir aapka account block ho jayega, turant verify karein",
            content.analysableText,
        )
        assertEquals(listOf(SharePartKind.TEXT), content.partKinds())
        assertTrue(read.isAnalysable)
        assertEquals(emptyList<String>(), read.issueCodes())
    }

    @Test
    fun bodyAndSubjectStaySeparatelyAddressable() {
        val read = ShareIntentReaderCore.read(
            snapshot(text = "KYC update karo", subject = "SBI alert"),
        )
        val content = accepted(read).share.content
        assertTrue(content is SharedContent.Text)
        assertEquals(listOf(SharePartKind.TEXT, SharePartKind.SUBJECT), content.partKinds())
        assertEquals("KYC update karo\nSBI alert", content.analysableText)
    }

    @Test
    fun identicalSubjectIsDedupedToASinglePart() {
        val read = ShareIntentReaderCore.read(
            snapshot(text = "Aapka parcel ruk gaya hai", subject = "Aapka parcel ruk gaya hai"),
        )
        val content = accepted(read).share.content
        assertEquals(listOf(SharePartKind.TEXT), content.partKinds())
        assertEquals("Aapka parcel ruk gaya hai", content.analysableText)
    }

    @Test
    fun titleIsUsedAsSubjectWhenNoSubjectWasSent() {
        val read = ShareIntentReaderCore.read(
            snapshot(text = "KYC update karo", title = "TrustShield sample"),
        )
        val content = accepted(read).share.content
        assertEquals(listOf(SharePartKind.TEXT, SharePartKind.SUBJECT), content.partKinds())
        assertEquals("TrustShield sample", content.parts[1].raw)
    }

    @Test
    fun linkOnlyBodyIsLinkAndNothingElse() {
        val read = ShareIntentReaderCore.read(snapshot(text = "https://bit.ly/3xYzP"))
        val content = accepted(read).share.content
        assertTrue("expected Link, got $content", content is SharedContent.Link)
        assertEquals("https://bit.ly/3xYzP", (content as SharedContent.Link).url)
        assertEquals(listOf(SharePartKind.LINK), content.partKinds())
        // The link is data only: the reader returns one analysable string and no stream handle.
        assertEquals("https://bit.ly/3xYzP", content.analysableText)
    }

    @Test
    fun bodyWithLinkBecomesTextAndLink() {
        val read = ShareIntentReaderCore.read(
            snapshot(text = "Yeh dekho https://bit.ly/xyz turant kholo"),
        )
        val content = accepted(read).share.content
        assertTrue("expected TextAndLink, got $content", content is SharedContent.TextAndLink)
        val mixed = content as SharedContent.TextAndLink
        assertEquals("https://bit.ly/xyz", mixed.url)
        assertEquals(listOf(SharePartKind.TEXT, SharePartKind.LINK), content.partKinds())
    }

    @Test
    fun multipleClipTextPartsKeepTheirOrder() {
        val read = ShareIntentReaderCore.read(
            snapshot(
                clipItemCount = 2,
                clipTextParts = listOf("Pehla hissa", "Doosra hissa"),
            ),
        )
        val content = accepted(read).share.content
        assertEquals("Pehla hissa\nDoosra hissa", content.analysableText)
    }

    @Test
    fun clipTextOnlyGmailShareIsAnalysedAndInventsNoImages() {
        val read = ShareIntentReaderCore.read(
            snapshot(
                clipItemCount = 1,
                clipTextParts = listOf("Aapka parcel custom clearance mein ruka hai, is number par call karein"),
            ),
        )
        val content = accepted(read).share.content
        assertTrue("expected Text, got $content", content is SharedContent.Text)
        assertFalse("a text clip must never become an image share", content is SharedContent.Images)
        assertTrue(content.isAnalysable)
    }

    @Test
    fun blankBodyIsReportedAsBlankTextNotAsNothing() {
        // A blank EXTRA_TEXT is a different fact from "the sender attached nothing".
        val read = ShareIntentReaderCore.read(snapshot(text = "   "))
        val result = refused(read)
        assertEquals(ShareEmptyReason.BLANK_TEXT, result.emptyReason)
        assertNull(result.reason)
        assertFalse(read.isAnalysable)
    }

    @Test
    fun noTextAndNoStreamIsNoContent() {
        val read = ShareIntentReaderCore.read(snapshot())
        assertEquals(ShareEmptyReason.NO_CONTENT, refused(read).emptyReason)
    }

    @Test
    fun emptyClipDeclarationIsRecorded() {
        val read = ShareIntentReaderCore.read(snapshot(clipItemCount = 2))
        val result = refused(read)
        assertEquals(ShareEmptyReason.NO_CONTENT, result.emptyReason)
        assertTrue(
            "empty clip must be recorded, was ${read.issueCodes()}",
            read.issueCodes().contains(ShareIntentIssue.EMPTY_CLIP.code),
        )
    }

    // ------------------------------------------------------------------ action validation

    @Test
    fun nullActionIsNoAction() {
        val read = ShareIntentReaderCore.read(snapshot(action = null))
        assertEquals(ShareEmptyReason.NO_ACTION, refused(read).emptyReason)
    }

    @Test
    fun blankActionIsNoAction() {
        val read = ShareIntentReaderCore.read(snapshot(action = "   "))
        assertEquals(ShareEmptyReason.NO_ACTION, refused(read).emptyReason)
    }

    @Test
    fun viewActionIsUnsupportedAction() {
        val read = ShareIntentReaderCore.read(
            snapshot(action = "android.intent.action.VIEW", dataUri = "content://com.example.provider/document/1"),
        )
        assertEquals(ShareEmptyReason.UNSUPPORTED_ACTION, refused(read).emptyReason)
    }

    // ------------------------------------------------------------------ images

    @Test
    fun singleClipImageWithTransientReadIsImages() {
        val read = ShareIntentReaderCore.read(
            snapshot(
                declaredMime = "image/png",
                clipItemCount = 1,
                clipUris = listOf(contentStream("photos/1", "image/png")),
                readGrant = ShareUriGrant.TransientRead,
            ),
        )
        val result = accepted(read)
        val content = result.share.content
        assertTrue("expected Images, got $content", content is SharedContent.Images)
        assertEquals("image/png", (content as SharedContent.Images).image.mime)
        assertEquals(ShareUriGrant.TransientRead, result.grant)
        assertTrue(read.isAnalysable)
        assertEquals("", content.analysableText)
    }

    @Test
    fun threeImageUrisBecomeMultipleImages() {
        val read = ShareIntentReaderCore.read(
            snapshot(
                action = ShareActions.ACTION_SEND_MULTIPLE,
                declaredMime = "image/*",
                clipItemCount = 3,
                clipUris = listOf(
                    contentStream("photos/1", "image/jpeg"),
                    contentStream("photos/2", "image/jpeg"),
                    contentStream("photos/3", "image/jpeg"),
                ),
            ),
        )
        val content = accepted(read).share.content
        assertTrue("expected MultipleImages, got $content", content is SharedContent.MultipleImages)
        assertEquals(3, (content as SharedContent.MultipleImages).images.size)
    }

    @Test
    fun sixImageUrisAreRefusedAsTooManyImages() {
        val uris = (1..6).map { contentStream("photos/$it", "image/png") }
        val read = ShareIntentReaderCore.read(
            snapshot(
                action = ShareActions.ACTION_SEND_MULTIPLE,
                declaredMime = "image/*",
                clipItemCount = 6,
                clipUris = uris,
            ),
        )
        assertEquals(ShareRejectReason.TOO_MANY_IMAGES, refused(read).reason)
    }

    @Test
    fun extraStreamImageIsPickedUpWhenClipIsEmpty() {
        val read = ShareIntentReaderCore.read(
            snapshot(
                declaredMime = "image/png",
                extraStreamUris = listOf(contentStream("photos/9", "image/png")),
            ),
        )
        val content = accepted(read).share.content
        assertTrue("expected Images, got $content", content is SharedContent.Images)
    }

    @Test
    fun intentDataUriIsTheLastResortStreamHandle() {
        val read = ShareIntentReaderCore.read(
            snapshot(
                action = ShareActions.ACTION_SEND_MULTIPLE,
                declaredMime = "image/png",
                dataUri = "content://com.example.provider/document/photos/7",
            ),
        )
        val content = accepted(read).share.content
        assertTrue("expected Images, got $content", content is SharedContent.Images)
    }

    @Test
    fun wildcardDeclaredImageDoesNotBecomeAGuessedFileType() {
        // "image/*" is a category, not a type: without provider metadata the type is unknown, and an
        // unknown image type is refused instead of being guessed as PNG.
        val read = ShareIntentReaderCore.read(
            snapshot(
                action = ShareActions.ACTION_SEND_MULTIPLE,
                declaredMime = "image/*",
                dataUri = "content://com.example.provider/document/photos/7",
            ),
        )
        assertEquals(ShareRejectReason.IMAGE_TYPE_NOT_ALLOWED, refused(read).reason)
    }

    @Test
    fun declaredImageWithoutAnyStreamIsRefusedNoUri() {
        val read = ShareIntentReaderCore.read(snapshot(declaredMime = "image/png"))
        assertEquals(ShareRejectReason.NO_URI, refused(read).reason)
    }

    @Test
    fun unsupportedImageTypeIsRefused() {
        val read = ShareIntentReaderCore.read(
            snapshot(
                declaredMime = "image/gif",
                clipItemCount = 1,
                clipUris = listOf(contentStream("photos/1", "image/gif")),
            ),
        )
        assertEquals(ShareRejectReason.IMAGE_TYPE_NOT_ALLOWED, refused(read).reason)
    }

    @Test
    fun imageShareHasNoAnalysableTextButCarriesAnalysableMaterial() {
        val read = ShareIntentReaderCore.read(
            snapshot(
                declaredMime = "image/png",
                clipUris = listOf(contentStream("photos/1", "image/png")),
            ),
        )
        val result = accepted(read)
        assertTrue(result.isAnalysable)
        assertEquals("", result.share.content.analysableText)
        assertNull(result.analysableOrNull())
    }

    // ------------------------------------------------------------------ uri scheme / grant safety

    @Test
    fun fileSchemeStreamIsRefusedAndNeverOpened() {
        val read = ShareIntentReaderCore.read(
            snapshot(
                declaredMime = "image/png",
                clipItemCount = 1,
                clipUris = listOf(
                    ShareIntentStreamSnapshot(
                        uriString = "file:///storage/emulated/0/Download/KYC.apk",
                        scheme = "file",
                        declaredMime = "image/png",
                    ),
                ),
            ),
        )
        val result = refused(read)
        assertEquals(ShareRejectReason.UNOPENABLE_URI, result.reason)
        assertTrue(
            "non-content scheme must be reported, was ${read.issueCodes()}",
            read.issueCodes().contains(ShareIntentIssue.NON_CONTENT_SCHEME.code),
        )
    }

    @Test
    fun everyNonContentSchemeIsRefused() {
        val schemes = listOf(
            "file" to "file:///sdcard/Download/a.png",
            "http" to "http://example.com/a.png",
            "https" to "https://example.com/a.png",
            "android.resource" to "android.resource://ai.trustshield.android/drawable/icon",
            "javascript" to "javascript:alert(1)",
            "intent" to "intent://example.com#Intent;scheme=https;end",
            // No scheme at all is not a content handle either.
            null to "/sdcard/Download/a.png",
        )
        for ((scheme, uri) in schemes) {
            val read = ShareIntentReaderCore.read(
                snapshot(
                    declaredMime = "image/png",
                    clipItemCount = 1,
                    clipUris = listOf(
                        ShareIntentStreamSnapshot(uriString = uri, scheme = scheme, declaredMime = "image/png"),
                    ),
                ),
            )
            assertTrue("scheme=$scheme must be refused, got $read", read is ShareIntentRead.Refused)
            assertTrue(
                "scheme=$scheme must record non_content_scheme, was ${read.issueCodes()}",
                read.issueCodes().contains(ShareIntentIssue.NON_CONTENT_SCHEME.code),
            )
        }
    }

    @Test
    fun schemeIsDerivedFromTheUriWhenTheSnapshotOmitsIt() {
        val read = ShareIntentReaderCore.read(
            snapshot(
                declaredMime = "image/png",
                clipUris = listOf(
                    ShareIntentStreamSnapshot(
                        uriString = "content://com.example.provider/document/photos/1",
                        scheme = null,
                        declaredMime = "image/png",
                    ),
                ),
            ),
        )
        val content = accepted(read).share.content
        assertTrue("expected Images, got $content", content is SharedContent.Images)
    }

    @Test
    fun contentStreamWithoutReadGrantIsRefusedAsUngranted() {
        val read = ShareIntentReaderCore.read(
            snapshot(
                declaredMime = "image/png",
                clipUris = listOf(contentStream("photos/1", "image/png")),
                readGrant = ShareUriGrant.None,
            ),
        )
        val result = refused(read)
        assertEquals(ShareRejectReason.UNOPENABLE_URI, result.reason)
        assertTrue(
            "missing read grant must be reported, was ${read.issueCodes()}",
            read.issueCodes().contains(ShareIntentIssue.NO_READ_GRANT.code),
        )
    }

    @Test
    fun persistableReadGrantIsCarriedOnTheAcceptedShare() {
        val read = ShareIntentReaderCore.read(
            snapshot(
                declaredMime = "image/png",
                clipUris = listOf(contentStream("photos/1", "image/png")),
                readGrant = ShareUriGrant.PersistableRead,
            ),
        )
        assertEquals(ShareUriGrant.PersistableRead, accepted(read).grant)
    }

    @Test
    fun grantMappingUsesOnlyFlagsAndThePresenceOfContentStreams() {
        // Values are Intent.FLAG_GRANT_READ_URI_PERMISSION / FLAG_GRANT_PERSISTABLE_URI_PERMISSION.
        assertEquals(0x00000001, ShareIntentFlags.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(0x00000040, ShareIntentFlags.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)

        assertSame(ShareUriGrant.None, ShareIntentFlags.grantOf(flags = 0, hasContentStream = true))
        assertSame(
            ShareUriGrant.TransientRead,
            ShareIntentFlags.grantOf(flags = ShareIntentFlags.FLAG_GRANT_READ_URI_PERMISSION, hasContentStream = true),
        )
        assertSame(
            ShareUriGrant.PersistableRead,
            ShareIntentFlags.grantOf(
                flags = ShareIntentFlags.FLAG_GRANT_READ_URI_PERMISSION or
                    ShareIntentFlags.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                hasContentStream = true,
            ),
        )
        // A persistable flag on its own grants nothing.
        assertSame(
            ShareUriGrant.None,
            ShareIntentFlags.grantOf(flags = ShareIntentFlags.FLAG_GRANT_PERSISTABLE_URI_PERMISSION, hasContentStream = true),
        )
        // No content handle in the share means there is no uri grant to claim.
        assertSame(
            ShareUriGrant.None,
            ShareIntentFlags.grantOf(flags = ShareIntentFlags.FLAG_GRANT_READ_URI_PERMISSION, hasContentStream = false),
        )
    }

    // ------------------------------------------------------------------ streams: dedupe and nulls

    @Test
    fun duplicateUriIsDedupedAndRecorded() {
        val same = contentStream("photos/1", "image/png")
        val read = ShareIntentReaderCore.read(
            snapshot(
                declaredMime = "image/png",
                clipItemCount = 2,
                clipUris = listOf(same, same.copy()),
            ),
        )
        val content = accepted(read).share.content
        assertTrue("one distinct uri must stay one image, got $content", content is SharedContent.Images)
        assertTrue(
            "duplicate must be recorded, was ${read.issueCodes()}",
            read.issueCodes().contains(ShareIntentIssue.DUPLICATE_STREAM.code),
        )
    }

    @Test
    fun nullUriEntryIsRefusedNoUriAndRecorded() {
        val read = ShareIntentReaderCore.read(
            snapshot(
                declaredMime = "image/png",
                clipItemCount = 1,
                clipUris = listOf(ShareIntentStreamSnapshot(uriString = null, scheme = null, declaredMime = "image/png")),
            ),
        )
        assertEquals(ShareRejectReason.NO_URI, refused(read).reason)
        assertTrue(
            "null entry must be recorded, was ${read.issueCodes()}",
            read.issueCodes().contains(ShareIntentIssue.NULL_STREAM_URI.code),
        )
    }

    // ------------------------------------------------------------------ declared types

    @Test
    fun apkDeclarationIsRefused() {
        val read = ShareIntentReaderCore.read(snapshot(declaredMime = ShareBounds.MIME_APK))
        assertEquals(ShareRejectReason.APK_ARCHIVE, refused(read).reason)
    }

    @Test
    fun htmlDeclarationIsRefused() {
        val read = ShareIntentReaderCore.read(snapshot(declaredMime = ShareBounds.MIME_HTML))
        assertEquals(ShareRejectReason.HTML_DOCUMENT, refused(read).reason)
    }

    @Test
    fun htmlTextOnlyIsNotAnalysableAndSaysSo() {
        val read = ShareIntentReaderCore.read(
            snapshot(htmlText = "<html><body>Aapka OTP 123456 batayein</body></html>"),
        )
        val result = refused(read)
        assertEquals(ShareEmptyReason.NO_CONTENT, result.emptyReason)
        assertTrue(
            "html must be declared ignored, was ${read.issueCodes()}",
            read.issueCodes().contains(ShareIntentIssue.HTML_TEXT_IGNORED.code),
        )
        assertNull(read.analysableOrNull())
    }

    @Test
    fun htmlTextIsIgnoredWhenPlainTextIsAlsoPresent() {
        val read = ShareIntentReaderCore.read(
            snapshot(text = "plain body only", htmlText = "<b>rich body</b>"),
        )
        val content = accepted(read).share.content
        assertEquals("plain body only", content.analysableText)
        assertTrue(read.issueCodes().contains(ShareIntentIssue.HTML_TEXT_IGNORED.code))
    }

    @Test
    fun providerGenericMimeDoesNotAccuseADeclaredImage() {
        val read = ShareIntentReaderCore.read(
            snapshot(
                declaredMime = "image/png",
                clipUris = listOf(contentStream("photos/1", "image/png", providerMime = "application/octet-stream")),
            ),
        )
        val content = accepted(read).share.content
        assertTrue("expected Images, got $content", content is SharedContent.Images)
        assertEquals("image/png", (content as SharedContent.Images).image.mime)
    }

    @Test
    fun providerMimeThatContradictsTheImageClaimIsRefused() {
        val read = ShareIntentReaderCore.read(
            snapshot(
                declaredMime = "image/png",
                clipUris = listOf(contentStream("photos/1", "image/png", providerMime = "application/pdf")),
            ),
        )
        assertEquals(ShareRejectReason.DECLARED_IMAGE_NOT_IMAGE, refused(read).reason)
    }

    // ------------------------------------------------------------------ review gate, determinism, privacy

    @Test
    fun reviewGateStaysPendingUntilTheUserAccepts() {
        val read = accepted(ShareIntentReaderCore.read(snapshot(text = "KYC update karo")))
        assertEquals(ReviewState.Pending, read.share.reviewState)
        assertTrue(read.share.requiresReview)
        // Analysable material exists, but the gated text is only readable after accept().
        assertTrue(read.isAnalysable)
        assertNull(read.analysableOrNull())
        assertEquals("KYC update karo", read.share.accept().analysableOrNull())
    }

    @Test
    fun readingTheSameSnapshotTwiceGivesAnEqualResult() {
        val snap = snapshot(
            action = ShareActions.ACTION_SEND_MULTIPLE,
            declaredMime = "image/*",
            clipItemCount = 2,
            clipUris = listOf(
                contentStream("photos/1", "image/png"),
                contentStream("photos/2", "image/webp"),
            ),
        )
        assertEquals(ShareIntentReaderCore.read(snap), ShareIntentReaderCore.read(snap))
    }

    @Test
    fun noToStringCarriesPayloadTextOrUri() {
        val textSnap = snapshot(
            text = "Aapka OTP 123456 kisi ko mat batana, turant batao",
            subject = "SBI KYC",
        )
        val imageSnap = snapshot(
            declaredMime = "image/png",
            clipUris = listOf(contentStream("photos/secret", "image/png")),
        )
        val printed = listOf(
            textSnap.toString(),
            imageSnap.toString(),
            imageSnap.clipUris.toString(),
            ShareIntentReaderCore.read(textSnap).toString(),
            ShareIntentReaderCore.read(imageSnap).toString(),
        ).joinToString("\n")

        assertFalse("payload text leaked: $printed", printed.contains("123456"))
        assertFalse("payload text leaked: $printed", printed.contains("turant batao"))
        assertFalse("subject leaked: $printed", printed.contains("SBI KYC"))
        assertFalse("provider leaked: $printed", printed.contains("com.example.provider"))
        assertFalse("provider path leaked: $printed", printed.contains("photos/secret"))
    }

    @Test
    fun toStringDoesNotPrintUntrustedActionOrMimeMetadata() {
        val marker = "PRIVATE_PAYLOAD_991122"
        val stream = ShareIntentStreamSnapshot(
            uriString = "content://example/$marker",
            scheme = marker,
            declaredMime = marker,
            providerMime = marker,
        )
        val share = ShareIntentSnapshot(
            action = marker,
            declaredMime = marker,
            clipUris = listOf(stream),
        )

        val printed = listOf(share.toString(), stream.toString()).joinToString("\n")
        assertFalse("untrusted metadata leaked: $printed", printed.contains(marker))
    }

    @Test
    fun acceptedShareToStringIsRedactedToo() {
        val read = accepted(
            ShareIntentReaderCore.read(
                snapshot(text = "OTP 998877 batado", declaredMime = "text/plain"),
            ),
        )
        val printed = read.share.toString()
        assertFalse(printed.contains("998877"))
        assertFalse(printed.contains("batado"))
    }

    @Test
    fun refusedReadHasExactlyOneReason() {
        val read = ShareIntentReaderCore.read(snapshot(action = null))
        val result = refused(read)
        // Either a reject code or an empty code, never both and never neither.
        assertTrue((result.reason == null) != (result.emptyReason == null))
    }

    @Test
    fun acceptedIssuesSurviveAnAdapterMergeWithoutDuplicates() {
        val read = accepted(ShareIntentReaderCore.read(snapshot(text = "hello there")))
        val merged = read.withIssues(
            listOf(ShareIntentIssue.MALFORMED_EXTRA, ShareIntentIssue.MALFORMED_EXTRA),
        )
        assertEquals(listOf(ShareIntentIssue.MALFORMED_EXTRA), merged.issues)
        assertTrue(merged is ShareIntentRead.Accepted)
    }
}
