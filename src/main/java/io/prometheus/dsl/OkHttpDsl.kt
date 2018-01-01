package io.prometheus.dsl

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

object OkHttpDsl {
    val OK_HTTP_CLIENT = OkHttpClient()

    inline fun String.get(block: (Response) -> Unit) {
        OK_HTTP_CLIENT
                .newCall(Request.Builder().url(this).build())
                .execute()
                .use(block)
    }
}
