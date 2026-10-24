package com.nuvio.app.features.streams
import com.nuvio.app.features.addons.RawHttpResponse
import kotlin.test.*
class SourceReadinessTest {
 private fun response(status:Int=200, type:String="", body:String="") = RawHttpResponse(status,"","https://test.invalid/media",body,mapOf("Content-Type" to type))
 @Test fun htmlErrorPageCannotGetGreenStatus() { assertEquals(SourceReadiness.FAILED,classifySourceResponse(response(type="video/mp4",body="<!doctype html><html>blocked</html>"))) }
 @Test fun forbiddenAndExpiredSourcesFail() { assertEquals(SourceReadiness.FAILED,classifySourceResponse(response(status=403))); assertEquals(SourceReadiness.FAILED,classifySourceResponse(response(status=404))) }
 @Test fun actualManifestIsRecognized() { assertEquals(SourceReadiness.REACHABLE,classifySourceResponse(response(body="#EXTM3U\n#EXTINF:6\nsegment.ts"))) }
 @Test fun successWithUnknownPayloadIsNotClaimedPlayable() { assertEquals(SourceReadiness.UNKNOWN,classifySourceResponse(response(body="OK"))) }
 @Test fun mediaTypeAcceptsPartialVideoResponse() { assertEquals(SourceReadiness.REACHABLE,classifySourceResponse(response(status=206,type="video/mp4"))) }
}
