package ru.nedotify.app.data

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.net.URLEncoder

class SignedUrlExpiryTest {
    private val policy="""{"Statement":[{"Resource":"https://host/ÿ￿","Condition":{"DateLessThan":{"AWS:EpochTime":2000}}},{"Condition":{"DateLessThan":{"AWS:EpochTime":1800}}}]}"""
    @Test fun `CloudFront policy can expire before the outer expires parameter`() {
        val encoded=Base64.getEncoder().encodeToString(policy.toByteArray(Charsets.UTF_8)).replace('+','-').replace('=','_').replace('/','~')
        assertEquals(1_740_000L,streamExpiry("https://cdn/p?expires=9000&Policy=$encoded",100))
    }
    @Test fun `percent encoded standard base64 and duplicate deadlines use earliest`() {
        val encoded=URLEncoder.encode(Base64.getEncoder().encodeToString(policy.toByteArray(Charsets.UTF_8)),"UTF-8")
        assertEquals(1_440_000L,streamExpiry("https://cdn/p?Expires=9000&expire=1500&Policy=$encoded",100))
    }
    @Test fun `a malformed signed policy is not cached under a later unsigned deadline`() {
        assertEquals(100L,streamExpiry("https://cdn/p?expires=9000&Policy=bad",100))
    }
    @Test fun `overflow cannot become a valid future expiration`() {
        assertEquals(840_100L,streamExpiry("https://cdn/p?expire=9223372036854775807",100))
        assertEquals(940_000L,streamExpiry("https://cdn/p?expire=1000&Expires=2000",100))
    }
}
