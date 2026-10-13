package com.pittech.devices
import org.junit.Assert.*
import org.junit.Test

class HomeAssistantAuthorizationTest {
    @Test fun authorizationNeverAcceptsAnotherOriginStateOrDuplicateCode() {
        val state = "nonce"
        val valid = HomeAssistantSession.REDIRECT + "?code=test-code&state=" + state
        assertEquals("test-code", HomeAssistantSession.authorizationCode(valid, state))
        assertNull(HomeAssistantSession.authorizationCode("https://attacker.test/ha/auth_callback?code=test-code&state=nonce", state))
        for (url in listOf(HomeAssistantSession.REDIRECT + "?code=x&state=wrong", valid + "&code=second")) {
            try { HomeAssistantSession.authorizationCode(url, state); fail("Unbound authorization accepted") } catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun nonceAndAuthorizeUrlAreBoundToTheChosenServer() {
        assertNotEquals(HomeAssistantSession.nonce(), HomeAssistantSession.nonce())
        val url = HomeAssistantSession.authorizationUrl("https://home.example.com:8123", "nonce")
        assertTrue(url.startsWith("https://home.example.com:8123/auth/authorize?"))
        assertTrue(url.contains("state=nonce"))
    }
    @Test fun localDnsRejectsPublicIpv4AndSupportsPrivateIpv6() {
        assertTrue(HomeAssistantLanDns.isLan(java.net.InetAddress.getByName("192.168.1.1")))
        assertTrue(HomeAssistantLanDns.isLan(java.net.InetAddress.getByName("fd00::1")))
        assertFalse(HomeAssistantLanDns.isLan(java.net.InetAddress.getByName("8.8.8.8")))
    }
}
