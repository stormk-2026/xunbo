package com.stormg.xunbo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stormg.xunbo.core.model.DeviceConnectionState
import com.stormg.xunbo.core.model.IrFrameFormat
import com.stormg.xunbo.core.model.RemoteKey
import com.stormg.xunbo.storage.ManualOutcome
import com.stormg.xunbo.storage.ManualRecord
import com.stormg.xunbo.ui.DebugViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as XunboApplication).container
        setContent {
            val vm: DebugViewModel =
                viewModel(
                    factory =
                        object : ViewModelProvider.Factory {
                            override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(DebugViewModel(container))!!
                        },
                )
            MaterialTheme { debugPage(container, vm) }
        }
    }
}

@Composable
private fun debugPage(
    container: AppContainer,
    vm: DebugViewModel,
) {
    val context = LocalContext.current
    val device by vm.device.collectAsState()
    val camera by vm.camera.collectAsState()
    val cameraInfo by vm.cameraInfo.collectAsState()
    val running by vm.running.collectAsState()
    val error by vm.serviceError.collectAsState()
    val devices by vm.devices.collectAsState()
    val codes by vm.codes.collectAsState()
    val pending by vm.pending.collectAsState()
    val transmitting by vm.transmitting.collectAsState()
    val format by vm.format.collectAsState()
    val message by vm.message.collectAsState()
    val learned by vm.learned.collectAsState()
    val plan by vm.importPlan.collectAsState()
    val records by vm.records.collectAsState()
    var selectedKey by remember { mutableStateOf(RemoteKey.UP) }
    var capture by remember { mutableStateOf(false) }
    var calibrating by remember { mutableStateOf(false) }
    var rectified by remember { mutableStateOf(false) }
    var deleteKey by remember { mutableStateOf<RemoteKey?>(null) }
    var confirmSend by remember { mutableStateOf<RemoteKey?>(null) }
    var labelRecord by remember { mutableStateOf<ManualRecord?>(null) }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted[Manifest.permission.CAMERA] == true ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            ) {
                vm.start()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    vm.message.value = "通知权限未授予；会话按系统规则运行，仍可在此页停止"
                }
            } else {
                vm.message.value = "相机权限被拒绝，可重新请求或在系统设置开启"
            }
        }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::import) }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let(vm::export) }
    Scaffold(bottomBar = {
        Button(onClick = vm::stop, modifier = Modifier.fillMaxWidth().padding(12.dp)) { Text("停止会话 · 清空待发") }
    }) { inset ->
        Column(
            Modifier.fillMaxSize().padding(inset).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("寻播 · M1 硬件调试", style = MaterialTheme.typography.headlineSmall)
            Text("手动单键验证。尚未接入自动识别、Jev 或自动任务。")
            Text(message)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("USB：${device.connection} ${device.deviceLabel.orEmpty()} ${device.errorCode.orEmpty()}")
            OutlinedButton(onClick = vm::refresh) { Text("刷新 USB 设备") }
            devices.forEach { descriptor ->
                OutlinedButton(onClick = { vm.connect(descriptor.id) }, enabled = !running && !device.learning) {
                    Text("连接 ${descriptor.label}")
                }
            }
            Text("串口 9600 / 8N1 · 初始 RAW4_INV：寻播尚待真机验证")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IrFrameFormat.entries.forEach { value ->
                    OutlinedButton(onClick = { vm.format(value) }, enabled = pending == 0 && !device.learning) {
                        Text(if (format == value) "✓ ${value.name}" else value.name)
                    }
                }
            }
            HorizontalDivider()
            Button(onClick = {
                val requested = mutableListOf(Manifest.permission.CAMERA)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) requested.add(Manifest.permission.POST_NOTIFICATIONS)
                permission.launch(requested.toTypedArray())
            }, enabled = !running) { Text("启动相机与调试会话") }
            Text("相机：$camera · $cameraInfo")
            Text("${if (running) "会话运行中" else "会话停止"} · 待执行/进行中：$pending · 正在发：${transmitting ?: "无"} · 学习：${device.learning}")
            Row {
                OutlinedButton(onClick = {
                    calibrating = !calibrating
                    rectified = false
                }, enabled = running) {
                    Text(if (calibrating) "退出标定" else "重新标定")
                }
                OutlinedButton(onClick = {
                    rectified = !rectified
                    calibrating = false
                }, enabled = running) {
                    Text(if (rectified) "完整取景" else "电视区域")
                }
            }
            if (calibrating) Text("首点后冻结画面：依次点击左上 → 右上 → 右下 → 左下。点错可退出重来。")
            AndroidView(factory = { container.camera.preview(it) }, modifier = Modifier.fillMaxWidth().height(240.dp), update = { preview ->
                if (preview.calibrating != calibrating || !running) preview.reset()
                preview.calibrating = calibrating
                preview.showRectified = rectified
                preview.onCalibration = { value ->
                    vm.calibrate(value)
                    calibrating = false
                }
                preview.invalidate()
            })
            HorizontalDivider()
            Text("按键库与单键试发", style = MaterialTheme.typography.titleLarge)
            RemoteKey.entries.chunked(4).forEach { group ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    group.forEach { key ->
                        TextButton(onClick = { selectedKey = key }) { Text("${if (key == selectedKey) "●" else ""}${key.name}") }
                    }
                }
            }
            Text("当前：$selectedKey / ${codes[selectedKey]?.let { "${it.userCode1}, ${it.userCode2}, ${it.commandCode}" } ?: "未学习"}")
            Row {
                Button(onClick = {
                    vm.learn(selectedKey)
                }, enabled = running && device.connection == DeviceConnectionState.READY && !device.learning && pending == 0) {
                    Text(
                        "学习此键 · 5 秒",
                    )
                }
                TextButton(onClick = vm::cancelLearn) { Text("取消学习") }
            }
            learned?.let { value ->
                Text("待保存：${value.name} = ${value.userCode1}, ${value.userCode2}, ${value.commandCode}")
                Button(onClick = vm::saveLearned) { Text(if (value.name in codes) "确认覆盖此键" else "保存学习结果") }
            }
            Row {
                Checkbox(checked = capture, onCheckedChange = { capture = it })
                Text("本次试发采集前后截图（后帧延迟 500ms，非稳定判定）")
            }
            Row {
                Button(onClick = {
                    if (selectedKey == RemoteKey.OK || selectedKey == RemoteKey.POWER) {
                        confirmSend = selectedKey
                    } else {
                        vm.send(selectedKey, capture)
                    }
                }, enabled = running && device.connection == DeviceConnectionState.READY && selectedKey in codes && !device.learning) {
                    Text("试发一键 $selectedKey")
                }
                TextButton(onClick = { deleteKey = selectedKey }, enabled = selectedKey in codes) { Text("删除此键") }
            }
            OutlinedButton(onClick = { importFile.launch(arrayOf("*/*")) }) { Text("选择旧 ir_keys.db 导入") }
            Text("请提供一致性导出的数据库；单独 db 文件可能遗漏 WAL 中的最新按键。")
            plan?.let { value ->
                Text("有效 ${value.candidates.size} / 未知 ${value.unknown} / 非法 ${value.invalid} / 冲突 ${value.conflicts}")
                Row {
                    Button(onClick = { vm.applyImport(false) }) { Text("导入 · 保留已有") }
                    TextButton(onClick = { vm.applyImport(true) }) { Text("确认覆盖冲突") }
                }
                TextButton(onClick = { vm.importPlan.value = null }) { Text("取消导入") }
            }
            HorizontalDivider()
            Text("手动记录 · ${records.size} 条", style = MaterialTheme.typography.titleLarge)
            Text("未标注不算成功；USB 写入成功不代表电视移动。固定配置连续 50 次方向键，至少 49 次移动 1 格。")
            Button(onClick = { exportFile.launch("xunbo-m1-${System.currentTimeMillis()}.zip") }) { Text("手动导出全部记录与关联截图") }
            records.take(100).forEach { record ->
                OutlinedButton(onClick = { labelRecord = record }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "${record.key} · ${record.outcome} · 排队 ${record.queueMs}ms / 写入 ${record.writeMs}ms\n" +
                            "${record.error ?: "写入返回，待人工判断"} · ${record.id.take(8)}",
                    )
                }
            }
            if (records.size > 100) Text("界面显示最近 100 条，导出包含全部记录。")
        }
    }
    deleteKey?.let { key ->
        AlertDialog(
            onDismissRequest = { deleteKey = null },
            title = { Text("删除 $key 的已保存码？") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(key)
                    deleteKey = null
                }) { Text("确认删除") }
            },
            dismissButton = { TextButton(onClick = { deleteKey = null }) { Text("取消") } },
        )
    }
    confirmSend?.let { key ->
        AlertDialog(
            onDismissRequest = { confirmSend = null },
            title = { Text("手动试发 $key") },
            text = { Text("请确认电视当前焦点安全。M1 尚不能识别扣费、删除或电源操作。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.send(key, capture)
                    confirmSend = null
                }) { Text("确认试发一次") }
            },
            dismissButton = { TextButton(onClick = { confirmSend = null }) { Text("取消") } },
        )
    }
    labelRecord?.let { record ->
        AlertDialog(
            onDismissRequest = { labelRecord = null },
            title = { Text("标注 ${record.key} 的实际效果") },
            text = {
                Column {
                    val labels = listOf("未标注", "移动 1 格", "无变化", "跳格", "方向错误", "不可判定")
                    ManualOutcome.entries.forEachIndexed { index, value ->
                        TextButton(onClick = {
                            vm.label(record, value)
                            labelRecord = null
                        }) { Text(labels[index]) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { labelRecord = null }) { Text("返回") } },
        )
    }
}
