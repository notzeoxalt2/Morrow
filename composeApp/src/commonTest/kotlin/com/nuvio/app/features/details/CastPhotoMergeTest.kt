package com.nuvio.app.features.details
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
class CastPhotoMergeTest {
 @Test fun fillsOnlyMatchingTitleCreditsAndRetainsOrder() {
  val cast=listOf(MetaPerson(name=" Adam   Scott "),MetaPerson(name="Britt Lower",photo="existing"),MetaPerson(name="Missing"))
  val result=mergeCastPhotos(cast,listOf(MetaPerson(name="Adam Scott",photo="adam",tmdbId=1),MetaPerson(name="Britt Lower",photo="replacement")))
  assertEquals(cast.map{it.name},result.map{it.name})
  assertEquals("adam",result[0].photo)
  assertEquals("existing",result[1].photo)
  assertNull(result[2].photo)
 }
 @Test fun rejectsAmbiguousNamesAndBlankNames() {
  val result=mergeCastPhotos(listOf(MetaPerson(name="Same"),MetaPerson(name=" ")),listOf(MetaPerson(name="Same",photo="a"),MetaPerson(name="Same",photo="b"),MetaPerson(name="",photo="wrong")))
  result.forEach{assertNull(it.photo)}
 }
 @Test fun personIdTakesPrecedenceOverName() {
  val result=mergeCastPhotos(listOf(MetaPerson(name="Localized",tmdbId=9)),listOf(MetaPerson(name="Localized",photo="wrong",tmdbId=8),MetaPerson(name="Original",photo="right",tmdbId=9)))
  assertEquals("right",result.single().photo)
 }
}
