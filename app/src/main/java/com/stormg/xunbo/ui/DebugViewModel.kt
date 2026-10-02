package com.stormg.xunbo.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stormg.xunbo.AppContainer
import com.stormg.xunbo.core.agent.ImportPlan
import com.stormg.xunbo.core.model.IrCode
import com.stormg.xunbo.core.model.IrDeviceDescriptor
import com.stormg.xunbo.core.model.IrFrameFormat
import com.stormg.xunbo.core.model.RemoteKey
import com.stormg.xunbo.core.model.ScreenCalibration
import com.stormg.xunbo.storage.ManualOutcome
import com.stormg.xunbo.storage.ManualRecord
import com.stormg.xunbo.storage.RecordRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DebugViewModel(private val container: AppContainer) : ViewModel() {
    private val debug = container.debug
    val device = debug.deviceStatus
    val camera = debug.cameraState
    val cameraInfo = container.previewInfo
    val running = container.running
    val serviceError = container.serviceError
    val codes = debug.codes
    val pending = debug.pending
    val transmitting = debug.transmitting
    val format = container.irFormat
    val devices = MutableStateFlow<List<IrDeviceDescriptor>>(emptyList())
    val message = MutableStateFlow("请先连接 USB，再启动相机会话；连接不会发键。")
    val learned = MutableStateFlow<IrCode?>(null)
    val importPlan = MutableStateFlow<ImportPlan?>(null)
    val records =
        container.dao.records().map { rows -> rows.map { Json.decodeFromString<ManualRecord>(it.json) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private var learning: Job? = null
    private var connecting: Job? = null

    init {
        refresh()
    }

    private fun launchOperation(block: suspend () -> Unit): Job =
        viewModelScope.launch {
            try {
                container.initialized.await()
                block()
            } catch (_: TimeoutCancellationException) {
                message.value = "操作超时，未保存学习数据"
            } catch (cancelled: CancellationException) {
                message.value = "操作已停止"
                throw cancelled
            } catch (_: Exception) {
                message.value = "操作失败；请检查权限、连接和会话状态后重试"
            }
        }

    fun refresh() {
        launchOperation { devices.value = debug.devices() }
    }

    fun connect(id: String) {
        if (connecting?.isActive == true) return
        connecting =
            launchOperation {
                debug.connect(id)
                message.value = "USB 已连接；请启动或重新启动会话后试发"
            }
    }

    fun start() {
        try {
            container.startSession()
        } catch (_: Exception) {
            message.value = "会话启动失败，请检查相机权限"
        }
    }

    fun stop() {
        debug.stop()
        learning?.cancel()
        connecting?.cancel()
        container.stopSession()
        message.value = "已停止；重新启动会话后才可继续发键"
    }

    fun format(value: IrFrameFormat) {
        launchOperation { debug.setFormat(value) }
    }

    fun send(
        key: RemoteKey,
        capture: Boolean,
    ) {
        launchOperation { container.send(key, capture) }
    }

    fun learn(key: RemoteKey) {
        if (learning?.isActive == true) return
        learned.value = null
        learning =
            launchOperation {
                learned.value = debug.learn(key)
                message.value = "学习结果尚未保存，请核对后点击保存"
            }
    }

    fun cancelLearn() {
        learning?.cancel()
        learned.value = null
    }

    fun saveLearned() {
        launchOperation {
            learned.value?.let { debug.save(it) }
            learned.value = null
        }
    }

    fun delete(key: RemoteKey) {
        launchOperation { debug.delete(key) }
    }

    fun import(uri: Uri) {
        launchOperation { importPlan.value = debug.previewImport(container.importer.read(uri)) }
    }

    fun applyImport(overwrite: Boolean) {
        launchOperation {
            importPlan.value?.let { debug.import(it, overwrite) }
            importPlan.value = null
            message.value = "导入事务已保存"
        }
    }

    fun calibrate(value: ScreenCalibration) {
        launchOperation {
            debug.saveCalibration(value)
            message.value = "标定已保存；请切到电视区域检查方向"
        }
    }

    fun label(
        record: ManualRecord,
        outcome: ManualOutcome,
    ) {
        launchOperation {
            container.dao.record(RecordRow(record.id, record.atMs, Json.encodeToString(record.copy(outcome = outcome))))
        }
    }

    fun export(uri: Uri) {
        launchOperation {
            container.exporter.write(uri)
            message.value = "导出成功：$uri"
        }
    }
}
