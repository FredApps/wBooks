package com.fredapp.wbooks.data.folder

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.fredapp.wbooks.transfer.FoldersJson
import com.fredapp.wbooks.transfer.WearProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class FolderSyncRepository(private val context: Context) {

    private val _state = MutableStateFlow(FoldersJson.State())
    val state: StateFlow<FoldersJson.State> = _state.asStateFlow()

    fun applyJson(json: String) {
        _state.value = FoldersJson.decode(json)
    }

    suspend fun loadInitial() = withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.Builder()
                .scheme("wear")
                .authority("*")
                .path(WearProtocol.PATH_FOLDERS)
                .build()
            val items = Wearable.getDataClient(context).getDataItems(uri).await()
            if (items.count > 0) {
                val json = DataMapItem.fromDataItem(items[0]).dataMap.getString("data")
                if (json != null) _state.value = FoldersJson.decode(json)
            }
            items.release()
        }
    }
}
