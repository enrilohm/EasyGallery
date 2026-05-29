package com.example.easygallery.db

import android.content.Context
import com.example.easygallery.MyObjectBox
import io.objectbox.BoxStore

object AppDatabase {
    private lateinit var store: BoxStore

    @Synchronized
    fun init(context: Context) {
        if (::store.isInitialized) return
        context.applicationContext.getDatabasePath("embeddings.db").delete()
        store = MyObjectBox.builder()
            .androidContext(context.applicationContext)
            .build()
    }

    fun getStore(): BoxStore = store
}
