package com.atube.app.extensions

import androidx.lifecycle.MutableLiveData

fun <T> MutableLiveData<T>.updateIfChanged(newValue: T) {
    if (value != newValue) value = newValue
}
