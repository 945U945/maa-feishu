package com.aliothmoon.maameow.utils.i18n

open class LocalizedException(val uiText: UiText, cause: Throwable? = null) : Exception(cause)
