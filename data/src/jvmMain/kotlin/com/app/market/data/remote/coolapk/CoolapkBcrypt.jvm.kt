package com.app.market.data.remote.coolapk

import org.mindrot.jbcrypt.BCrypt

internal actual fun coolapkBcrypt(value: String, salt: String): String = BCrypt.hashpw(value, salt)
