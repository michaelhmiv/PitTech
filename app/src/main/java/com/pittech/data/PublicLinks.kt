package com.pittech.data

import java.net.URI

/** Navigation only: public links never authenticate, import or publish. */
internal object PublicLink {
    fun destination(input: String): String {
        val uri=URI(input)
        require(uri.rawQuery==null && uri.rawFragment==null && uri.rawUserInfo==null && uri.port == -1 && uri.rawPath==uri.path)
        val parts=uri.path.split('/')
        val kind: String;val value: String
        if(uri.scheme=="https" && uri.rawAuthority=="pittech.app" && parts.size==3 && parts[0].isEmpty()) { kind=parts[1];value=parts[2] }
        else if(uri.scheme=="pittech" && uri.rawAuthority in setOf("recipe","profile") && parts.size==2 && parts[0].isEmpty()) { kind=if(uri.host=="recipe")"c" else "p";value=parts[1] }
        else error("Unsupported public link")
        if(kind=="c" && value.matches(Regex("[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")))return value.lowercase(java.util.Locale.ROOT)
        require(kind=="p" && value.matches(Regex("[a-z0-9_]{3,24}")))
        return "profile:$value"
    }
}
