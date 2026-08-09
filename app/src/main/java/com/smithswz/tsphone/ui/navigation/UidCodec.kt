package com.smithswz.tsphone.ui.navigation

/**
 * TS3 UIDs are base64 and contain '/' and '+', which break navigation routes.
 * base64's alphabet has neither '_' nor '-', so the substitution is lossless.
 */
object UidCodec {
    fun encode(uid: String): String = uid.replace('/', '_').replace('+', '-')
    fun decode(encoded: String): String = encoded.replace('_', '/').replace('-', '+')
}
