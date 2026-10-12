package com.cofbro.qian.profile

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup.MarginLayoutParams
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import com.cofbro.hymvvmutils.base.BaseFragment
import com.cofbro.hymvvmutils.base.getBySp
import com.cofbro.hymvvmutils.base.saveUsedSp
import com.cofbro.qian.account.manager.AccountManagerActivity
import com.cofbro.qian.data.URL
import com.cofbro.qian.databinding.FragmentProfileBinding
import com.cofbro.qian.login.LoginActivity
import com.cofbro.qian.utils.AccountManager
import com.cofbro.qian.utils.AmapKeyStore
import com.cofbro.qian.utils.CacheUtils
import com.cofbro.qian.utils.Constants
import com.cofbro.qian.utils.Downloader
import com.cofbro.qian.utils.PresetLocationManager
import com.cofbro.qian.utils.dp2px
import com.cofbro.qian.utils.getStatusBarHeight
import com.cofbro.qian.view.dialog.SignReportDialog
import com.alibaba.fastjson.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class ProfileFragment : BaseFragment<ProfileViewModel, FragmentProfileBinding>() {
    override fun onAllViewCreated(savedInstanceState: Bundle?) {
        initObserver()
        doNetWork()
        initView()
        initEvent()
    }

    private fun initObserver() {
    }

    private fun doNetWork() {
    }

    private fun initView() {
        adjustMarginOfView()
        adjustContainerHeight()
        profileMessageInfo()
        checkSignWithStatus()
        updatePresetLocationStatus()
        updateAmapKeyStatus()
    }

    private fun adjustContainerHeight() {
        binding?.tvLogin?.post {
            val bottomHeight = CacheUtils.cache[Constants.Cache.BOTTOM_BAR_HEIGHT]?.toInt() ?: 0
            val layout = binding?.tvLogin?.layoutParams as? MarginLayoutParams
            layout?.bottomMargin = bottomHeight
            binding?.tvLogin?.layoutParams = layout
//            val layout = binding?.profileContainer?.layoutParams
//            layout?.height = (binding?.profileContainer?.height ?: 0) + bottomHeight * 3
//            binding?.profileContainer?.layoutParams = layout
        }
    }

    private fun checkSignWithStatus() {
        val switch = requireActivity().getBySp("signWith")?.toBoolean() ?: false
        binding?.signWithButton?.isChecked = switch
    }

    private fun adjustMarginOfView() {
        val statusBarHeight = getStatusBarHeight(requireContext())
        val layoutParams = binding?.csMyInfo?.layoutParams as? MarginLayoutParams
        layoutParams?.topMargin = statusBarHeight + dp2px(requireContext(), 5)
    }

    @SuppressLint("SetTextI18n")
    private fun profileMessageInfo() {
        viewModel.uid.let {
            val options = RequestOptions().transform(
                CenterCrop(),
                RoundedCorners(dp2px(requireContext(), 5))
            )
            Glide.with(this)
                .load(URL.getAvtarImgPath(it))
                .apply(options)
                .into(binding!!.ivProfileUserIcon)
        }

        // 先显示缓存名, 然后异步获取真实姓名
        binding?.tvProfileUsername?.text = CacheUtils.cache["realName"]
            ?: CacheUtils.cache[Constants.USER.USERNAME] ?: "-"

        binding?.tvProfileId?.text = "uid: ${CacheUtils.cache[Constants.USER.UID] ?: "-"}"

        // 异步获取真实姓名
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cookies = CacheUtils.getCurrentCookies()
                if (cookies.isNotEmpty()) {
                    val name = fetchRealNameCSF(cookies)
                    if (!name.isNullOrEmpty()) {
                        CacheUtils.setRealName(name)
                        withContext(Dispatchers.Main) {
                            binding?.tvProfileUsername?.text = name
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    /** CSF对齐: POST加密设备信息到userLogin4Uname.do获取真实姓名 */
    private suspend fun fetchRealNameCSF(cookies: String): String? = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
                .followRedirects(false).build()
            var body: String? = null
            // 方式1: POST加密设备信息 (CSF主方式)
            try {
                val encryptedInfo = com.cofbro.qian.utils.DeviceInfoHelper.buildEncryptedDeviceInfo(requireContext())
                val req = Request.Builder().url("https://sso.chaoxing.com/apis/login/userLogin4Uname.do")
                    .addHeader("cookie", cookies)
                    .addHeader("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 14_2_1 like Mac OS X)")
                    .post(okhttp3.FormBody.Builder().add("data", encryptedInfo).build()).build()
                body = client.newCall(req).execute().use { it.body?.string() ?: "" }
            } catch (_: Exception) {}
            // 方式2: GET fallback
            if (body.isNullOrEmpty()) {
                val req = Request.Builder().url("https://sso.chaoxing.com/apis/login/userLogin4Uname.do")
                    .addHeader("cookie", cookies)
                    .addHeader("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 14_2_1 like Mac OS X)")
                    .get().build()
                body = client.newCall(req).execute().use { it.body?.string() ?: "" }
            }
            JSONObject.parseObject(body ?: "").getJSONObject("msg")?.getString("name")
        } catch (_: Exception) { null }
    }


    private fun initEvent() {
        binding?.tvLogin?.setOnClickListener {
            viewModel.logoutDialog = LogoutDialog(requireContext()).apply {
                setCancelClickListener {
                    viewModel.logoutDialog?.dismiss()
                }
                setConfirmClickListener {
                    val intent = Intent(requireActivity(), LoginActivity::class.java)
                    clearUserInfo(context)
                    startActivity(intent)
                    requireActivity().finish()
                }
                setCancelable(false)
                show()
            }
        }

        binding?.bindAccounts?.setOnClickListener {
            val intent = Intent(requireActivity(), AccountManagerActivity::class.java)
            startActivity(intent)
        }

        binding?.signWithButton?.setOnClickListener {
            val switch = it as SwitchCompat
            requireActivity().saveUsedSp("signWith", switch.isChecked.toString())
        }

        binding?.signRecords?.setOnClickListener {
            SignReportDialog(requireContext()).show()
        }

        binding?.exportCookie?.setOnClickListener {
            exportCookie()
        }

        binding?.importCookie?.setOnClickListener {
            importCookie()
        }

        binding?.amapKeySetting?.setOnClickListener {
            showAmapKeySettingDialog()
        }

        binding?.presetLocation?.setOnClickListener {
            showPresetLocationDialog()
        }

        binding?.helpGuide?.setOnClickListener {
            showHelpGuideDialog()
        }
    }

    private fun updateAmapKeyStatus() {
        val isUserKey = AmapKeyStore.isUsingUserKey()
        binding?.tvAmapKeyStatus?.text = if (isUserKey) "已配置" else "未配置"
        binding?.tvAmapKeyStatus?.setTextColor(resources.getColor(
            if (isUserKey) android.R.color.holo_green_dark else android.R.color.darker_gray, null))
    }

    private fun showAmapKeySettingDialog() {
        val currentKey = AmapKeyStore.getApiKey()
        val isUserKey = AmapKeyStore.isUsingUserKey()

        val editText = EditText(requireContext()).apply {
            hint = "输入高德地图API Key"
            setText(if (isUserKey) currentKey else "")
            setPadding(48, 24, 48, 24)
            setTextSize(13f)
            setSingleLine()
        }

        val scrollView = android.widget.ScrollView(requireContext()).apply {
            addView(editText)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("高德地图API Key 设置")
            .setMessage("开源后内置Key可能失效。\n请前往 lbs.amap.com 注册账号获取Key。\n当前Key: ${currentKey.take(8)}****${currentKey.takeLast(4)}")
            .setView(scrollView)
            .setPositiveButton("保存") { _, _ ->
                val key = editText.text.toString().trim()
                if (key.isNotEmpty()) {
                    AmapKeyStore.setApiKey(requireContext(), key)
                    updateAmapKeyStatus()
                    Toast.makeText(requireContext(), "API Key已保存", Toast.LENGTH_SHORT).show()
                } else {
                    AmapKeyStore.clearApiKey(requireContext())
                    updateAmapKeyStatus()
                    Toast.makeText(requireContext(), "已恢复默认Key", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("清除") { _, _ ->
                AmapKeyStore.clearApiKey(requireContext())
                updateAmapKeyStatus()
                Toast.makeText(requireContext(), "已清除自定义Key", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showHelpGuideDialog() {
        val helpText = """
            📚 学习通签到助手 - 使用攻略

            ⚙️ 首次使用配置
            ▸ 高德地图API Key
              开源版内置Key可能失效。
              前往 lbs.amap.com 注册 → 应用管理 → 创建应用
              → 添加Key(Android平台, SHA1随意) → 复制Key
              在"个人中心→高德Key"中粘贴保存。
            ▸ 预设照片
              拍照验证签到需要。个人中心→预设照片→从相册选择。
            ▸ 预留位置
              定位签到需要。个人中心→预留地址→添加常用位置。
            ▸ 登录账号
              超星学习通账号密码登录。

            🔹 扫码签到（推荐）
            首页右上角扫码 → 扫描老师展示的二维码 → 自动完成。
            扫码即签, 无需进课程找活动。
            支持: 普通/二维码/定位

            🔹 智能签到
            签到前先查 ifNeedVCode 等参数, 提前处理好验证码
            再一次性签到, 不浪费enc。

            🔹 定位签到
            个人中心→预留地址 设置常用位置。
            签到自动尝试当前位置, 失败后遍历预留地址。

            🔹 手势签到 / 签到码
            进课程 → 活动列表 → 手势/签到码活动 → "输入密码"
            画出手势(或输入签到码)提交, 输错可原地重试。

            🔹 代签功能
            个人中心开启"代签所有绑定账号"。
            签到成功后自动为绑定账号签到。
            代签账号建议同时保存手机号+密码(支持自动重登)。

            🔹 签到报告
            首页"LOG"或 个人中心→签到报告,
            查看签到全过程日志, 可复制反馈。

            🔹 注意事项
            • 定位签到需要开启GPS
            • 二维码签到enc一次性, 失败需重新扫码
            • 需要人脸/拍照验证的签到无法自动提交
            • 代签需提前绑定账号
            • 签到失败时, 签到报告里有完整过程可排查
        """.trimIndent()

        val scrollView = android.widget.ScrollView(requireContext())
        val textView = android.widget.TextView(requireContext()).apply {
            text = helpText
            textSize = 13f
            setPadding(24, 16, 24, 16)
            setLineSpacing(8f, 1.0f)
            setTextIsSelectable(true)
        }
        scrollView.addView(textView)
        AlertDialog.Builder(requireContext())
            .setTitle("📖 使用攻略")
            .setView(scrollView)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun exportCookie() {
        val cookies = CacheUtils.getCurrentCookies()
        if (cookies.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "当前没有Cookie可导出", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try { fetchRealNameCSF(cookies)?.let { CacheUtils.setRealName(it) } } catch (_: Exception) {}
            val fullJson = CacheUtils.getExportCookieJson()       // 含加密密码
            val noPwdJson = buildExportJsonWithoutPassword(fullJson) // 不含密码
            withContext(Dispatchers.Main) {
                AlertDialog.Builder(requireContext())
                    .setTitle("导出Cookie")
                    .setMessage("请选择导出方式:\n\n• 完整导出: 含加密密码, 可自动续期\n• 无密码导出: 仅Cookie, 约5天后失效需重新导出")
                    .setPositiveButton("完整导出(推荐)") { _, _ ->
                        copyToClipboard(fullJson, "Cookie(含密码)已复制到剪贴板")
                    }
                    .setNegativeButton("无密码导出") { _, _ ->
                        copyToClipboard(noPwdJson, "Cookie(无密码)已复制到剪贴板, 约5天后失效")
                    }
                    .setNeutralButton("取消", null)
                    .show()
            }
        }
    }

    private fun buildExportJsonWithoutPassword(fullJson: String): String {
        return try {
            val json = com.alibaba.fastjson.JSONObject.parseObject(fullJson)
            json.remove("password")
            json.remove("phone")
            json.toJSONString()
        } catch (_: Exception) { fullJson }
    }

    private fun copyToClipboard(text: String, toastMsg: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("cookie", text))
        Toast.makeText(requireContext(), toastMsg, Toast.LENGTH_LONG).show()
    }

    private fun importCookie() {
        val layout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val etPhone = EditText(requireContext()).apply {
            hint = "手机号(可选,用于自动重登)"
            setSingleLine()
            textSize = 13f
        }
        val etPassword = EditText(requireContext()).apply {
            hint = "密码(可选,用于自动重登)"
            setSingleLine()
            textSize = 13f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val etCookie = EditText(requireContext()).apply {
            hint = "粘贴Cookie JSON"
            setPadding(0, 12, 0, 0)
            setTextSize(12f)
            minLines = 3
            maxLines = 6
        }
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0)?.text?.toString() ?: ""
            if (text.contains("\"cookies\"")) {
                etCookie.setText(text)
            }
        }
        layout.addView(etPhone)
        layout.addView(etPassword)
        layout.addView(etCookie)

        AlertDialog.Builder(requireContext())
            .setTitle("导入账号(代签)")
            .setView(layout)
            .setPositiveButton("导入") { _, _ ->
                val phone = etPhone.text.toString().trim()
                val password = etPassword.text.toString().trim()
                val input = etCookie.text.toString().trim()
                if (input.isEmpty() && phone.isEmpty()) {
                    Toast.makeText(requireContext(), "请输入Cookie或手机号", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                try {
                    var cookies = ""
                    var uid = ""
                    var fid = ""
                    var username = "代签用户"

                    if (input.isNotEmpty()) {
                        val json = com.alibaba.fastjson.JSONObject.parseObject(input)
                        cookies = json.getString("cookies") ?: ""
                        uid = json.getString("uid") ?: ""
                        fid = json.getString("fid") ?: ""
                        // 优先用真实姓名 > username
                        username = json.getString("name")?.takeIf { it.isNotEmpty() }
                            ?: json.getString("username") ?: username
                        if (phone.isEmpty() && json.containsKey("phone")) {
                            etPhone.setText(json.getString("phone"))
                        }
                        if (password.isEmpty() && json.containsKey("password")) {
                            etPassword.setText(json.getString("password"))
                        }
                    }

                    val finalPhone = etPhone.text.toString().trim()
                    val finalPassword = etPassword.text.toString().trim()

                    if (cookies.isEmpty() && uid.isEmpty()) {
                        Toast.makeText(requireContext(), "缺少Cookie或UID", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    saveImportedAccount(cookies, uid, fid, username, finalPhone, finalPassword)
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "解析失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun saveImportedAccount(cookies: String, uid: String, fid: String, username: String, phone: String, password: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val accountData = AccountManager.loadAllAccountData(requireContext())
            val account = com.alibaba.fastjson.JSONObject().apply {
                put(Constants.Account.USERNAME, if (phone.isNotEmpty()) phone else username)
                put(Constants.Account.PASSWORD, AccountManager.encryptPassword(password))
                put(Constants.Account.UID, uid)
                put(Constants.Account.COOKIE, cookies)
                put(Constants.Account.FID, fid)
                put(Constants.Account.PIC_URL, URL.getAvtarImgPath(uid))
                put(Constants.Account.REMARK, username)
            }
            val updatedData = AccountManager.bindAccounts(requireContext(), accountData, account)
            if (updatedData != null) {
                AccountManager.updateAccountData(requireContext(), updatedData.toJSONString())
                launch(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "账号导入成功！支持自动重登", Toast.LENGTH_LONG).show()
                }
            } else {
                launch(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "导入失败（账号已存在？）", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updatePresetLocationStatus() {
        val presets = PresetLocationManager.loadAll(requireContext())
        if (presets.isNotEmpty()) {
            binding?.tvPresetLocationStatus?.text = "${presets.size}个地址"
            binding?.tvPresetLocationStatus?.setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
        } else {
            binding?.tvPresetLocationStatus?.text = "未设置"
            binding?.tvPresetLocationStatus?.setTextColor(resources.getColor(android.R.color.darker_gray, null))
        }
    }

    private fun showPresetLocationDialog() {
        val presets = PresetLocationManager.loadAll(requireContext())
        val items = mutableListOf<String>()
        for ((i, loc) in presets.withIndex()) {
            items.add("${i + 1}. ${loc.name.ifEmpty { loc.address.ifEmpty { "地址" + (i+1) } }} (${loc.latitude}, ${loc.longitude})")
        }
        items.add("+ 添加新地址")

        AlertDialog.Builder(requireContext())
            .setTitle("预留地址 (${presets.size}个)")
            .setItems(items.toTypedArray()) { _, which ->
                if (which == presets.size) {
                    showAddPresetLocationDialog()
                } else {
                    showPresetLocationDetailDialog(which, presets[which])
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showAddPresetLocationDialog() {
        val layout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val etName = EditText(requireContext()).apply {
            hint = "名称, 如: 教学楼A"
            setSingleLine()
            textSize = 13f
        }
        val etLat = EditText(requireContext()).apply {
            hint = "纬度(latitude), 如: 21.192620"
            setSingleLine()
            textSize = 13f
        }
        val etLon = EditText(requireContext()).apply {
            hint = "经度(longitude), 如: 110.257421"
            setSingleLine()
            textSize = 13f
        }
        val etAddr = EditText(requireContext()).apply {
            hint = "详细地址(可选), 如: 广东海洋大学"
            setSingleLine()
            textSize = 13f
        }
        layout.addView(etName)
        layout.addView(etLat)
        layout.addView(etLon)
        layout.addView(etAddr)

        // 添加"查看坐标"提示链接
        val tvCoordHint = android.widget.TextView(requireContext()).apply {
            text = "点击查看坐标: lbs.baidu.com/maptool/getpoint"
            setTextColor(android.graphics.Color.parseColor("#1E88E5"))
            textSize = 12f
            setPadding(0, 12, 0, 0)
            setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://lbs.baidu.com/maptool/getpoint"))
                startActivity(intent)
            }
        }
        layout.addView(tvCoordHint)

        AlertDialog.Builder(requireContext())
            .setTitle("添加预留地址")
            .setView(layout)
            .setPositiveButton("添加") { _, _ ->
                val name = etName.text.toString().trim()
                val lat = etLat.text.toString().trim()
                val lon = etLon.text.toString().trim()
                val addr = etAddr.text.toString().trim()
                if (lat.isEmpty() || lon.isEmpty()) {
                    Toast.makeText(requireContext(), "经纬度不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                try {
                    PresetLocationManager.add(requireContext(), PresetLocationManager.PresetLocation(
                        name = name, address = addr, latitude = lat.toDouble(), longitude = lon.toDouble()
                    ))
                    updatePresetLocationStatus()
                    Toast.makeText(requireContext(), "已添加: ${name.ifEmpty { addr }}", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "经纬度格式错误", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showPresetLocationDetailDialog(index: Int, loc: PresetLocationManager.PresetLocation) {
        AlertDialog.Builder(requireContext())
            .setTitle(loc.name.ifEmpty { loc.address.ifEmpty { "地址" + (index + 1) } })
            .setMessage("纬度: ${loc.latitude}\n经度: ${loc.longitude}\n地址: ${loc.address}")
            .setPositiveButton("编辑") { _, _ ->
                showEditPresetLocationDialog(index, loc)
            }
            .setNeutralButton("删除") { _, _ ->
                PresetLocationManager.remove(requireContext(), index)
                updatePresetLocationStatus()
                Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showEditPresetLocationDialog(index: Int, loc: PresetLocationManager.PresetLocation) {
        val layout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val etName = EditText(requireContext()).apply {
            hint = "名称"
            setText(loc.name)
            setSingleLine()
            textSize = 13f
        }
        val etLat = EditText(requireContext()).apply {
            hint = "纬度"
            setText(loc.latitude.toString())
            setSingleLine()
            textSize = 13f
        }
        val etLon = EditText(requireContext()).apply {
            hint = "经度"
            setText(loc.longitude.toString())
            setSingleLine()
            textSize = 13f
        }
        val etAddr = EditText(requireContext()).apply {
            hint = "详细地址"
            setText(loc.address)
            setSingleLine()
            textSize = 13f
        }
        layout.addView(etName)
        layout.addView(etLat)
        layout.addView(etLon)
        layout.addView(etAddr)

        AlertDialog.Builder(requireContext())
            .setTitle("编辑预留地址")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text.toString().trim()
                val lat = etLat.text.toString().trim()
                val lon = etLon.text.toString().trim()
                val addr = etAddr.text.toString().trim()
                try {
                    val presets = PresetLocationManager.loadAll(requireContext()).toMutableList()
                    presets[index] = PresetLocationManager.PresetLocation(
                        name = name, address = addr, latitude = lat.toDouble(), longitude = lon.toDouble()
                    )
                    PresetLocationManager.saveAll(requireContext(), presets)
                    updatePresetLocationStatus()
                    Toast.makeText(requireContext(), "已保存", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "经纬度格式错误", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun clearUserInfo(context: Context) {
        context.saveUsedSp("username", "")
        context.saveUsedSp("password", "")
        CacheUtils.clearPersistedCookies()
        deleteHomeJSON()
        Toast.makeText(context, "数据删除成功", Toast.LENGTH_SHORT).show()
    }


    private fun deleteHomeJSON() {
        lifecycleScope.launch(Dispatchers.IO) {
            Downloader.delete(requireContext(), Constants.RecycleJson.HOME_JSON_DATA)
        }
    }
}